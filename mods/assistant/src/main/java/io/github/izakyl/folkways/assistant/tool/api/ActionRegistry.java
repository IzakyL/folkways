package io.github.izakyl.folkways.assistant.tool.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.core.colony.charter.CurrentColony;
import io.github.izakyl.folkways.core.front.api.Ghost;
import io.github.izakyl.jpi.ai.SchemaValidator;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class ActionRegistry {

    public static final int CONFIRMATION_TICKS = 1800;

    public static final ActionRegistry ACTIONS = new ActionRegistry();

    public record Spec(ToolSpec tool, String category, boolean destructive, boolean explicitApproval) {

        public Spec(ToolSpec tool, String category, boolean destructive) {
            this(tool, category, destructive, false);
        }
    }

    /** raise, where not null, applies the change at once in creative instead of handing it to the colony. */
    public record Plan(Component summary, JsonObject changes, String state, Supplier<CompletableFuture<ToolReply>> apply,
            List<Ghost> ghosts, Supplier<CompletableFuture<ToolReply>> raise) {

        public Plan(Component summary, JsonObject changes, String state, Supplier<CompletableFuture<ToolReply>> apply,
                List<Ghost> ghosts) {
            this(summary, changes, state, apply, ghosts, null);
        }

        public Plan(Component summary, JsonObject changes, String state, Supplier<CompletableFuture<ToolReply>> apply) {
            this(summary, changes, state, apply, List.of());
        }
    }

    @FunctionalInterface
    public interface AsyncPreparation {

        CompletableFuture<Plan> prepare(ToolContext context, JsonObject args, Supplier<ToolContext> current);
    }

    private record Entry(Spec spec, BiFunction<ToolContext, JsonObject, Plan> prepare,
            AsyncPreparation prepareAsync, boolean visible) {
    }

    public static final class Refused extends IllegalArgumentException {

        private final String code;

        private final Component explanation;

        public Refused(String code) {
            this(code, code, null);
        }

        public Refused(String code, Component explanation) {
            this(code, code, explanation);
        }

        public Refused(String error, String code, Component explanation) {
            super(error);
            this.code = code;
            this.explanation = explanation;
        }
    }

    private final Map<String, Entry> entries = new TreeMap<>();

    private final Map<ServerPlayer, Map<String, Long>> used = new WeakHashMap<>();

    private final byte[] secret = new byte[32];

    public ActionRegistry() {
        new SecureRandom().nextBytes(secret);
    }

    public void register(Spec spec, BiFunction<ToolContext, JsonObject, Plan> prepare) {
        register(spec, prepare, (context, args, current) ->
                CompletableFuture.completedFuture(prepare.apply(context, args)));
    }

    public void register(Spec spec, BiFunction<ToolContext, JsonObject, Plan> prepare,
            AsyncPreparation prepareAsync) {
        entries.put(spec.tool().id(), new Entry(spec, prepare, prepareAsync, true));
    }

    /** Register an operation used by the script host without publishing a standalone action. */
    public void registerInternal(Spec spec, BiFunction<ToolContext, JsonObject, Plan> prepare) {
        entries.put(spec.tool().id(), new Entry(spec, prepare, (context, args, current) ->
                CompletableFuture.completedFuture(prepare.apply(context, args)), false));
    }

    public List<Spec> specs() {
        return entries.values().stream().filter(Entry::visible).map(Entry::spec).toList();
    }

    public ToolReply preview(ServerPlayer player, String name, JsonObject args) {
        try {
            var context = context(player);
            Entry entry = entry(name, args);
            Plan plan = entry.prepare().apply(context, args);
            return preview(player, name, args, context, entry, plan);
        } catch (Refused refusal) {
            return error(refusal);
        } catch (IllegalArgumentException refusal) {
            return ToolReply.invalid(refusal.getMessage());
        }
    }

    public CompletableFuture<ToolReply> previewAsync(ServerPlayer player, String name, JsonObject args) {
        try {
            var context = context(player);
            var entry = entry(name, args);
            return prepareAsync(player, name, args).thenApply(plan ->
                    preview(player, name, args, context, entry, plan)).exceptionally(ActionRegistry::error);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(error(invalid));
        }
    }

    private ToolReply preview(ServerPlayer player, String name, JsonObject args, ToolContext context,
            Entry entry, Plan plan) {
        JsonObject data = plan.changes().deepCopy();
        data.addProperty("revision", revision(player, name, args, plan, player.serverLevel().getGameTime()
                + ":" + UUID.randomUUID()));
        data.addProperty("category", entry.spec().category());
        data.addProperty("destructive", entry.spec().destructive());
        data.addProperty("explicit_approval", entry.spec().explicitApproval());
        data.addProperty("colony_id", context.colony().id().toString());
        data.addProperty("capture_tick", player.serverLevel().getGameTime());
        JsonObject text = new JsonObject();
        text.addProperty("$text", 0);
        data.add("summary", text);
        return new ToolReply(data, List.of(plan.summary()), plan.ghosts()).capped(player.registryAccess());
    }

    private CompletableFuture<Plan> prepareAsync(ServerPlayer player, String name, JsonObject args) {
        var context = context(player);
        try {
            return entry(name, args).prepareAsync().prepare(context, args, () -> context(player));
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
    }

    public static ToolReply error(Throwable failure) {
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof Refused refused) {
            ToolReply reply = ToolReply.error(refused.getMessage());
            reply.data().addProperty("code", refused.code);
            if (refused.explanation != null) {
                JsonObject text = new JsonObject();
                text.addProperty("$text", 0);
                reply.data().add("refusal", text);
                return new ToolReply(reply.data(), List.of(refused.explanation));
            }
            return reply;
        }
        return ToolReply.invalid(failure instanceof IllegalArgumentException ? failure.getMessage() : null);
    }

    public CompletableFuture<Plan> validateAsync(ServerPlayer player, String name, JsonObject args, String revision) {
        try {
            context(player);
            // The confirmation must arrive before expiry. Rebuilding its plan can span many ticks.
            String issued = checkAge(player, revision);
            return prepareAsync(player, name, args).handle((plan, failure) -> {
                if (failure != null) {
                    ToolReply reply = error(failure);
                    throw new Refused(reply.data().get("error").getAsString().equals("unloaded")
                            ? "unloaded" : "stale");
                }
                return validate(player, name, args, revision, issued, plan);
            });
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
    }

    public CompletableFuture<ToolReply> apply(ServerPlayer player, String name, JsonObject args, String revision) {
        try {
            Plan plan = validate(player, name, args, revision);
            return plan.apply().get().thenApply(reply -> reply.capped(player.registryAccess()));
        } catch (Refused refusal) {
            return CompletableFuture.completedFuture(error(refusal));
        } catch (IllegalArgumentException refusal) {
            return CompletableFuture.completedFuture(ToolReply.invalid(refusal.getMessage()));
        }
    }

    Plan validate(ServerPlayer player, String name, JsonObject args, String revision) {
        var context = context(player);
        Entry entry = entry(name, args);
        String issued = checkAge(player, revision);
        Plan plan;
        try {
            plan = entry.prepare().apply(context, args);
        } catch (Refused changed) {
            if (changed.getMessage().equals("unloaded")) {
                throw changed;
            }
            throw new Refused("stale");
        }
        return validate(player, name, args, revision, issued, plan);
    }

    private static String checkAge(ServerPlayer player, String revision) {
        String[] parts = revision.split(":", 3);
        if (parts.length != 3) {
            throw new Refused("stale");
        }
        long tick = Long.parseLong(parts[0]);
        long now = player.serverLevel().getGameTime();
        if (tick > now || now - tick > CONFIRMATION_TICKS) {
            throw new Refused("stale");
        }
        return parts[0] + ":" + parts[1];
    }

    private Plan validate(ServerPlayer player, String name, JsonObject args, String revision, String issued,
            Plan plan) {
        context(player);
        long now = player.serverLevel().getGameTime();
        checkRevision(player, name, args, revision, issued, plan);
        Map<String, Long> consumed = used.computeIfAbsent(player, ignored -> new HashMap<>());
        consumed.entrySet().removeIf(item -> now - item.getValue() > CONFIRMATION_TICKS);
        if (consumed.putIfAbsent(revision, now) != null) {
            throw new Refused("stale");
        }
        return plan;
    }

    private void checkRevision(ServerPlayer player, String name, JsonObject args, String revision, String issued,
            Plan plan) {
        String expected = revision(player, name, args, plan, issued);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                revision.getBytes(StandardCharsets.UTF_8))) {
            throw new Refused("stale");
        }
    }

    /** Rebuild the plan at its turn in the batch and check it against the already validated approval. */
    public CompletableFuture<ToolReply> applyApproved(ServerPlayer player, String name, JsonObject args,
            String revision, boolean raise) {
        try {
            context(player);
            if (!used.getOrDefault(player, Map.of()).containsKey(revision)) {
                throw new Refused("stale");
            }
            String issued = revision.substring(0, revision.lastIndexOf(':'));
            var dimension = player.serverLevel().dimension();
            return prepareAsync(player, name, args).handle((plan, failure) -> {
                if (failure != null) {
                    String code = error(failure).data().get("error").getAsString();
                    throw new Refused(code.equals("unloaded") ? "unloaded" : "stale");
                }
                context(player);
                if (!player.serverLevel().dimension().equals(dimension)) {
                    throw new Refused("stale");
                }
                checkRevision(player, name, args, revision, issued, plan);
                return plan;
            }).thenCompose(plan -> (raise && player.isCreative() && plan.raise() != null
                    ? plan.raise() : plan.apply()).get()).exceptionally(failure -> {
                Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                if (cause instanceof IllegalArgumentException) {
                    return error(cause);
                }
                throw new CompletionException(cause);
            });
        } catch (Refused refusal) {
            return CompletableFuture.completedFuture(error(refusal));
        }
    }

    public void arguments(String name, JsonObject args) {
        entry(name, args);
    }

    private ToolContext context(ServerPlayer player) {
        if (!player.getServer().isSameThread()) {
            throw new IllegalStateException("Actions must run on the server thread");
        }
        return new ToolContext(player, CurrentColony.of(player).orElseThrow(() -> new Refused("not_member")));
    }

    private Entry entry(String name, JsonObject args) {
        Entry entry = entries.get(name);
        if (entry == null) {
            throw new Refused("unsupported_action_charter_razing_upload_and_creative_are_excluded");
        }
        var errors = SchemaValidator.validate(entry.spec().tool().schema(), args);
        if (!errors.isEmpty()) {
            throw new Refused("invalid_arguments", Component.literal(name + ": " + ToolRegistry.describeSchemaErrors(errors)));
        }
        return entry;
    }

    private String revision(ServerPlayer player, String name, JsonObject args, Plan plan, String issued) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String subject = issued + "/" + player.getUUID() + "/" + CurrentColony.of(player).orElseThrow().id()
                    + "/" + name + "/" + canonical(args) + "/" + canonical(plan.changes()) + "/" + plan.state();
            return issued + ":" + HexFormat.of().formatHex(mac.doFinal(subject.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String canonical(JsonElement value) {
        if (value.isJsonObject()) {
            Map<String, String> sorted = new TreeMap<>();
            value.getAsJsonObject().entrySet()
                    .forEach(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            return sorted.toString();
        }
        if (value.isJsonArray()) {
            return value.getAsJsonArray().asList().stream().map(ActionRegistry::canonical).toList().toString();
        }
        return value.toString();
    }
}
