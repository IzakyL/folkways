package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.assistant.tool.api.CaptureRequest;
import io.github.izakyl.folkways.assistant.tool.api.StateRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.core.script.api.ColonyScripts;
import io.github.izakyl.folkways.core.script.api.Profile;
import io.github.izakyl.folkways.core.script.api.ScriptFault;
import io.github.izakyl.folkways.core.script.api.ScriptRuntime;
import io.github.izakyl.folkways.core.script.api.Workers;
import io.github.izakyl.folkways.core.script.api.ScriptValues;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;
import net.starlark.java.syntax.Location;
import org.slf4j.Logger;

@EventBusSubscriber(modid = AssistantMod.ID)
public final class ScriptHost {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation TREE = ResourceLocation.fromNamespaceAndPath("folkways_std", "tree");
    private static final String TREE_SOURCE = treeSource();

    public static final ScriptHost INSTANCE = new ScriptHost();

    public static final int MAX_INTENTS = 32;

    static final int MAX_PROBES = 2;

    private static final ResourceLocation REQUEST =
        ResourceLocation.fromNamespaceAndPath("folkways_assistant", "request");

    private final Map<ServerPlayer, Running> running = new WeakHashMap<>();

    private final ToolRegistry reads;

    private final StateRegistry states = StateRegistry.STATES;

    private final ActionBatch batches = new ActionBatch(states.operations());

    private final Profile profile;
    private final ScriptRuntime runtime;

    public record Client(JsonObject view, String scratch, boolean imageInput, String language,
            Function<String, CompletableFuture<JsonElement>> history) {
    }

    // A null scratch means the client should keep its previous snapshot.
    public record RunReply(ToolReply reply, String scratch, List<JsonObject> captures, int historyReads) {

        public RunReply(ToolReply reply) {
            this(reply, null, List.of(), 0);
        }
    }

    private record Output(ToolReply reply, List<StateRegistry.Change> changes, List<JsonObject> captures) {

        private Output(ToolReply reply, List<StateRegistry.Change> changes) {
            this(reply, changes, List.of());
        }
    }

    private static final class Running {

        private volatile boolean cancelled;
        private volatile boolean readingHistory;
        private ScriptClientMount mount;

        private final CompletableFuture<RunReply> reply = new CompletableFuture<>();

        private void fail(String code) {
            reply.complete(result(ToolReply.error(code), List.of()));
        }

        private RunReply result(ToolReply value, List<JsonObject> captures) {
            return new RunReply(value, mount == null ? null : mount.updated(), captures,
                    mount == null ? 0 : mount.historyReads());
        }
    }

    private static String treeSource() {
        try (var source = ScriptHost.class.getResourceAsStream("/assets/folkways_assistant/std/tree.star")) {
            if (source == null) {
                throw new IllegalStateException("Missing built-in script module tree");
            }
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot read built-in script module tree", failed);
        }
    }

    public ScriptHost() {
        this(Profile.agent(), ReadTools.create());
    }

    public ScriptHost(long steps, long milliseconds, int calls) {
        this(steps, milliseconds, calls, ReadTools.create());
    }

    public ScriptHost(long steps, long milliseconds, int calls, ToolRegistry reads) {
        this(Profile.agent().withLimits(steps, Duration.ofMillis(milliseconds), calls), reads);
    }

    private ScriptHost(Profile profile, ToolRegistry reads) {
        this.reads = reads;
        this.profile = profile.withLibraryResolver(label ->
            label.equals("@std//tree") ? TREE : profile.libraryResolver().apply(label));
        this.runtime = new ScriptRuntime(this.profile);
    }

    public CompletableFuture<ToolReply> run(ServerPlayer player, String source, JsonObject view) {
        return run(player, source, view, "{}").thenApply(RunReply::reply);
    }

    public CompletableFuture<RunReply> run(ServerPlayer player, String source, JsonObject view, String scratch) {
        return run(player, source, new Client(view, scratch, false, "en_us",
                path -> CompletableFuture.failedFuture(new IllegalStateException("Client history is unavailable"))));
    }

    public CompletableFuture<RunReply> run(ServerPlayer player, String source, Client client) {
        if (!player.getServer().isSameThread()) {
            throw new IllegalStateException("Submit scripts on the server thread; never wait there");
        }
        if (!reads.admit(player)) {
            return CompletableFuture.completedFuture(new RunReply(ToolReply.error("rate_limited")));
        }
        if (running.containsKey(player)) {
            return CompletableFuture.completedFuture(new RunReply(ToolReply.error("already_running")));
        }
        try {
            runtime.checkSource(source);
        } catch (ScriptFault fault) {
            return CompletableFuture.completedFuture(new RunReply(ToolReply.error("script_size_cap")));
        }
        if (!ScriptValues.fitsScratch(client.scratch())) {
            return CompletableFuture.completedFuture(new RunReply(ToolReply.error("scratch_size_cap")));
        }
        var snapshot = states.snapshot(player);
        var dimension = player.serverLevel().dimension();
        Running task = new Running();
        try {
            task.mount = new ScriptClientMount(client.view(), client.scratch(),
                    path -> readHistory(player, task, client, path));
        } catch (JsonParseException | IllegalStateException invalid) {
            return CompletableFuture.completedFuture(new RunReply(ToolReply.error("invalid_scratch")));
        }
        running.put(player, task);
        boolean privileged = WorldReads.permitted(player);
        CompletableFuture<Output> execution;
        try {
            execution = runtime.submit(() -> execute(player, source, client, privileged, snapshot, task));
        } catch (ScriptFault busy) {
            running.remove(player, task);
            task.fail("busy");
            return task.reply;
        }
        execution.whenComplete((output, failure) -> {
            if (task.cancelled || player.getServer().isStopped()) {
                task.fail("cancelled");
                return;
            }
            player.getServer().execute(() -> {
                try {
                    if (task.cancelled) {
                        task.fail("cancelled");
                    } else if (failure != null) {
                        ScriptFault fault = ScriptRuntime.fault(failure);
                        if (fault.kind() == ScriptFault.Kind.EVAL) {
                            LOGGER.warn("Assistant script failed", fault);
                        }
                        task.fail(errorCode(fault));
                    } else {
                        batches.previewState(player, states, snapshot, dimension, output.changes(),
                                output.reply(), () -> task.cancelled)
                                .whenComplete((reply, broken) -> {
                                    running.remove(player, task);
                                    if (task.cancelled) {
                                        task.fail("cancelled");
                                    } else if (broken != null) {
                                        task.fail("script_failed");
                                    } else {
                                        boolean success = !reply.data().has("error") && !reply.data().has("truncated");
                                        task.reply.complete(task.result(reply, success ? output.captures() : List.of()));
                                    }
                                });
                    }
                } catch (RuntimeException broken) {
                    LOGGER.warn("Assistant preview failed", broken);
                    task.fail("script_failed");
                } finally {
                    if (task.reply.isDone()) {
                        running.remove(player, task);
                    }
                }
            });
        });
        return task.reply;
    }

    public CompletableFuture<ToolReply> decide(ServerPlayer player, String revision, boolean accept) {
        return decide(player, revision, accept, false);
    }

    public CompletableFuture<ToolReply> decide(ServerPlayer player, String revision, boolean accept, boolean raise) {
        if (!reads.admit(player)) {
            return CompletableFuture.completedFuture(ToolReply.error("rate_limited"));
        }
        return batches.decide(player, revision, accept, raise);
    }

    private Output execute(ServerPlayer player, String source, Client client, boolean privileged,
            StateRegistry.Snapshot snapshot, Running task) {
        ScriptValues values = ScriptValues.output(ToolReply.MAX_BYTES);
        JsonArray prints = new JsonArray();
        int[] drafts = {0};
        int[] probes = {0};
        List<JsonObject> captures = new ArrayList<>();
        String[] code = {null};
        ToolReply[] bindingFailure = {null};
        ScriptRuntime.BindingBudget budget = runtime.newBudget();
        ScriptState state = new ScriptState(states, snapshot, budget::charge, code, task.mount);
        Map<String, Object> bindings = ScriptApi.bindings(privileged, (function, args, thread) -> {
            budget.charge();
            if (function.name().equals("help")) {
                return ScriptApi.help(args.has("name") ? args.get("name").getAsString() : null,
                        privileged, client.language());
            }
            if (function.name().equals("capture")) {
                if (!client.imageInput()) {
                    code[0] = "image_input_disabled";
                    throw Starlark.errorf("capture requires image input to be enabled for the model");
                }
                JsonObject camera;
                int images;
                try {
                    camera = ScriptApi.camera(args);
                    images = CaptureRequest.images(camera);
                } catch (IllegalArgumentException | ClassCastException | IllegalStateException invalid) {
                    code[0] = "invalid_arguments";
                    throw Starlark.errorf("capture: %s", invalid.getMessage());
                }
                int queued = captures.stream().mapToInt(CaptureRequest::images).sum();
                if (queued + images > CaptureRequest.MAX_PER_RUN) {
                    code[0] = "capture_limit";
                    throw Starlark.errorf("At most %d images per script", CaptureRequest.MAX_PER_RUN);
                }
                captures.add(camera);
                return Starlark.NONE;
            }
            JsonObject query = args;
            ToolReply reply;
            if (function.name().equals("controls")) {
                reply = server(player.getServer(), task, () -> FacilityTools.controls(player, query));
            } else if (function.name().equals("draft") || function.name().equals("mechanics")
                    || function.name().equals("mechanism") || function.name().equals("probe")
                    || function.name().startsWith("world.")) {
                if (function.name().equals("draft") && ++drafts[0] > 4) {
                    code[0] = "draft_limit";
                    throw Starlark.errorf("At most 4 drafts per script");
                }
                if (function.name().equals("probe") && ++probes[0] > MAX_PROBES) {
                    code[0] = "probe_limit";
                    throw Starlark.errorf("At most %d probes per script", MAX_PROBES);
                }
                CompletableFuture<ToolReply> reading = server(player.getServer(), task,
                        () -> ToolRegistry.DIRECT_READS.execute(player, function.spec().id(), query));
                reply = function.name().equals("probe") && query.has("routine")
                    ? Workers.awaitExternal(150, TimeUnit.SECONDS, () -> await(player.getServer(), task, reading))
                    : await(player.getServer(), task, reading);
            } else {
                reply = server(player.getServer(), task, () -> reads.execute(player, function.target(), query));
            }
            if (reply.data().has("error")) {
                bindingFailure[0] = reply;
                code[0] = reply.data().get("error").getAsString();
                throw Starlark.errorf("%s: %s", function.name(), code[0]);
            }
            return ScriptValues.readOutput(reply.data(), reply.texts(), thread.mutability());
        });
        state.bind(bindings);
        bindings.put("print", new StarlarkCallable() {
            @Override
            public String getName() {
                return "print";
            }

            @Override
            public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs) throws EvalException {
                if (kwargs.size() > 1 || (!kwargs.isEmpty() && !kwargs.containsKey("sep"))) {
                    throw Starlark.errorf("print accepts only sep as a keyword");
                }
                Object separator = kwargs.getOrDefault("sep", " ");
                if (!(separator instanceof String)) {
                    throw Starlark.errorf("sep must be a string");
                }
                JsonObject line = new JsonObject();
                line.add("values", values.write(args));
                line.add("sep", values.write(separator));
                prints.add(line);
                return Starlark.NONE;
            }
        });
        try {
            task.mount.validate();
        } catch (EvalException | NumberFormatException invalid) {
            return error("invalid_scratch", "Expected a scratch dictionary containing plain data", null);
        }
        try {
            ScriptRuntime execution = new ScriptRuntime(profile.withBindings(bindings));
            return execution.evaluate(execution.compile(REQUEST, source), budget, id -> {
                if (id.equals(TREE)) {
                    return Optional.of(TREE_SOURCE);
                }
                var value = snapshot.get("scripts/" + ColonyScripts.name(id));
                return value.isJsonNull() ? Optional.empty() : Optional.of(value.getAsString());
            }, (module, thread) -> {
                JsonObject data = new JsonObject();
                data.add("prints", prints);
                Object result = module.getGlobal("result");
                if (result != null) {
                    data.add("result", values.write(result));
                }
                return new Output(new ToolReply(data, values.texts()), state.changes(),
                        List.copyOf(captures));
            });
        } catch (ScriptFault fault) {
            String error = code[0] == null ? errorCode(fault) : code[0];
            if (error.equals("script_failed")) {
                throw fault;
            }
            Output failure = error(error, fault.detail(), fault.location());
            if (bindingFailure[0] != null) {
                JsonObject data = failure.reply().data().deepCopy();
                bindingFailure[0].data().entrySet().forEach(entry -> data.add(entry.getKey(), entry.getValue()));
                return new Output(new ToolReply(data, bindingFailure[0].texts()), List.of());
            }
            return failure;
        }
    }

    private static JsonElement readHistory(ServerPlayer player, Running task, Client client, String path)
            throws EvalException, InterruptedException {
        task.readingHistory = true;
        try {
            MinecraftServer server = player.getServer();
            ScriptRuntime.checkTime();
            CompletableFuture<JsonElement> reading = new CompletableFuture<>();
            reading.orTimeout(5, TimeUnit.SECONDS);
            server.execute(() -> {
                if (task.cancelled || reading.isDone()) {
                    return;
                }
                try {
                    var request = client.history().apply(path);
                    reading.whenComplete((data, failure) -> request.cancel(false));
                    request.whenComplete((data, failure) -> {
                        if (failure == null) {
                            reading.complete(data);
                        } else {
                            reading.completeExceptionally(failure);
                        }
                    });
                } catch (RuntimeException failure) {
                    reading.completeExceptionally(failure);
                }
            });
            try {
                return await(server, task, reading);
            } catch (IllegalStateException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof TimeoutException) {
                    throw Starlark.errorf("Client history read timed out after 5 seconds");
                }
                throw Starlark.errorf("Client history read failed: %s", cause.getMessage());
            }
        } finally {
            task.readingHistory = false;
        }
    }

    private static String errorCode(ScriptFault fault) {
        return switch (fault.kind()) {
            case SYNTAX -> "syntax_error";
            case EVAL -> !(fault.getCause() instanceof EvalException) ? "script_failed"
                : fault.location() == null ? "invalid_result" : "eval_error";
            case LOAD -> "eval_error";
            case STEPS -> "step_limit";
            case MEMORY -> "memory_limit";
            case TIMEOUT -> "timeout";
            case CALLS -> "call_limit";
            case SOURCE_SIZE -> "script_size_cap";
            case RESULT_SIZE -> "result_size_cap";
            case CANCELLED -> "cancelled";
            case BUSY -> "busy";
            case FUNCTION -> "script_failed";
        };
    }

    private static Output error(String code, String message, Location location) {
        JsonObject data = ToolReply.error(code).data();
        data.addProperty("message", message);
        if (location != null) {
            data.addProperty("line", location.line());
            data.addProperty("column", location.column());
        }
        return new Output(new ToolReply(data, List.of()), List.of());
    }

    private static <T> T server(MinecraftServer server, Running task, Supplier<T> work) throws InterruptedException {
        ScriptRuntime.checkTime();
        CompletableFuture<T> future = server.submit(() -> {
            if (task.cancelled) {
                throw new IllegalStateException("cancelled");
            }
            return work.get();
        });
        return await(server, task, future);
    }

    private static <T> T await(MinecraftServer server, Running task, CompletableFuture<T> future)
            throws InterruptedException {
        try {
            while (!task.cancelled && !server.isStopped()) {
                try {
                    return future.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException pending) {
                    ScriptRuntime.checkTime();
                } catch (ExecutionException failed) {
                    throw new IllegalStateException(failed.getCause());
                }
            }
            throw new InterruptedException("server stopped or player disconnected");
        } finally {
            future.cancel(false);
        }
    }

    private void cancel(ServerPlayer player) {
        Running task = running.remove(player);
        if (task != null) {
            boolean reading = task.readingHistory;
            task.cancelled = true;
            task.fail(reading ? "client_read_disconnected" : "cancelled");
        }
        batches.forget(player);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            INSTANCE.cancel(player);
            Surveys.forget(player);
        }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        List.copyOf(INSTANCE.running.keySet()).forEach(INSTANCE::cancel);
        Surveys.clear();
    }
}
