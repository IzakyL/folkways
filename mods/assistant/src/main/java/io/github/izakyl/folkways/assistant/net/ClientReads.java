package io.github.izakyl.folkways.assistant.net;

import com.google.gson.JsonElement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;

final class ClientReads {

    private record Pending(ServerPlayer player, CompletableFuture<JsonElement> result) {
    }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    CompletableFuture<JsonElement> read(ServerPlayer player, UUID runId, String path, Consumer<ClientRead> send) {
        if (player.hasDisconnected()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Player disconnected"));
        }
        if (path.length() > 128) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid client history path"));
        }
        ClientRead request = new ClientRead(runId, UUID.randomUUID(), path);
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        Pending read = new Pending(player, future);
        pending.put(request.requestId(), read);
        future.whenComplete((data, failure) -> pending.remove(request.requestId(), read));
        try {
            send.accept(request);
        } catch (RuntimeException failure) {
            future.completeExceptionally(failure);
        }
        return future;
    }

    void receive(ServerPlayer player, ClientReadReply reply) {
        Pending read = pending.get(reply.requestId());
        if (read == null || read.player() != player || read.result().isDone()) {
            return;
        }
        try {
            JsonElement data = reply.data();
            if (reply.success()) {
                read.result().complete(data);
            } else {
                read.result().completeExceptionally(new IllegalStateException(data.getAsString()));
            }
        } catch (RuntimeException invalid) {
            read.result().completeExceptionally(new IllegalArgumentException("Invalid client history reply"));
        }
    }

    void disconnect(ServerPlayer player) {
        for (Pending read : pending.values()) {
            if (read.player() == player) {
                read.result().completeExceptionally(new IllegalStateException("Player disconnected"));
            }
        }
    }
}
