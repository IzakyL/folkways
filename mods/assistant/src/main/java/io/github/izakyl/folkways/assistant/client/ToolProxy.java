package io.github.izakyl.folkways.assistant.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.net.ActionRequest;
import io.github.izakyl.folkways.assistant.net.ClientRead;
import io.github.izakyl.folkways.assistant.net.ClientReadReply;
import io.github.izakyl.folkways.assistant.net.ToolRequest;
import io.github.izakyl.folkways.assistant.net.ToolResponse;
import io.github.izakyl.folkways.assistant.tool.ConfirmedAction;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost.RunReply;
import io.github.izakyl.jpi.ai.CancellationToken;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

final class ToolProxy implements ConfirmedAction.Transport {

    private record Pending(CompletableFuture<ToolResponse> result, History history) {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    @FunctionalInterface
    interface History {
        JsonElement read(String path) throws IOException;
    }

    private final Supplier<JsonObject> view;
    private final BooleanSupplier imageInput;
    private final History history;

    private final Predicate<String> raising;

    private final AtomicBoolean running = new AtomicBoolean();

    private volatile String scratch = "{}";

    ToolProxy(Supplier<JsonObject> view, BooleanSupplier imageInput, History history, Predicate<String> raising) {
        this.view = view;
        this.imageInput = imageInput;
        this.history = history;
        this.raising = raising;
    }

    String scratch() {
        return scratch;
    }

    void restoreScratch(String saved) {
        scratch = saved;
    }

    @Override
    public CompletableFuture<RunReply> run(String script, CancellationToken cancellation) {
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(new RunReply(ToolReply.error("already_running")));
        }
        return request(id -> new ToolRequest(id, script, view.get().toString(), scratch, imageInput.getAsBoolean(),
                        Minecraft.getInstance().options.languageCode), cancellation, history, 315)
                .thenApply(response -> {
                    RunReply result = response.result();
                    if (result.scratch() != null && !cancellation.isCancelled()) {
                        scratch = result.scratch();
                    }
                    return result;
                }).whenComplete((response, failure) -> running.set(false));
    }

    @Override
    public CompletableFuture<ToolReply> decide(String revision, boolean accept, CancellationToken cancellation) {
        boolean raise = accept && raising.test(revision);
        return request(id -> new ActionRequest(id, revision, accept, raise), cancellation, null, 15)
                .thenApply(response -> response.result().reply());
    }

    private static CompletableFuture<ToolResponse> request(Function<UUID, CustomPacketPayload> packet,
            CancellationToken cancellation, History history, int timeoutSeconds) {
        UUID id = UUID.randomUUID();
        CompletableFuture<ToolResponse> future = new CompletableFuture<>();
        PENDING.put(id, new Pending(future, history));
        var subscription = cancellation.onCancel(() -> future.cancel(false));
        future.orTimeout(timeoutSeconds, TimeUnit.SECONDS).whenComplete((reply, error) -> {
            PENDING.remove(id);
            subscription.close();
        });
        Minecraft.getInstance().execute(() -> {
            if (future.isDone()) {
                return;
            }
            if (Minecraft.getInstance().getConnection() == null) {
                future.cancel(false);
                return;
            }
            try {
                PacketDistributor.sendToServer(packet.apply(id));
            } catch (RuntimeException failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    static void receive(ToolResponse response) {
        Pending request = PENDING.get(response.requestId());
        if (request != null) {
            request.result().complete(response);
        }
    }

    static void receive(ClientRead request) {
        Pending run = PENDING.get(request.runId());
        if (run == null || run.history() == null || run.result().isDone()) {
            return;
        }
        ClientReadReply reply;
        try {
            reply = ClientReadReply.success(request.requestId(), run.history().read(request.path()));
        } catch (IOException | IllegalArgumentException failure) {
            reply = ClientReadReply.failure(request.requestId(), failure.getMessage() == null
                    ? "Saved session unavailable" : failure.getMessage());
        } catch (RuntimeException failure) {
            reply = ClientReadReply.failure(request.requestId(), "Saved session unavailable");
        }
        if (!run.result().isDone() && Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(reply);
        }
    }

    static void disconnect() {
        PENDING.values().forEach(request -> request.result().cancel(false));
        PENDING.clear();
    }
}
