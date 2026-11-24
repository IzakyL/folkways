package io.github.izakyl.folkways.assistant.tool.api;

import com.google.gson.JsonObject;
import io.github.izakyl.folkways.core.front.api.Ghost;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.minecraft.network.chat.Component;

public final class ConfirmationQueue {

    /**
     * One change in a pending decision: its words, where it stands in the world if anywhere, and whether it builds,
     * which a creative player may have set down at once.
     */
    public record Change(Component brief, Optional<JsonObject> place, boolean builds) {
    }

    /** raise holds the creative player's choice to build at once rather than hand the builds to the colony. */
    public record Pending(Component subject, List<Change> changes, List<Ghost> ghosts, BooleanSupplier valid,
            CompletableFuture<Boolean> decision, AtomicBoolean raise) {
    }

    private final List<Pending> pending = new ArrayList<>();

    public Pending add(Component subject, List<Change> changes, List<Ghost> ghosts, BooleanSupplier valid) {
        Pending item = new Pending(subject, List.copyOf(changes), ghosts, valid, new CompletableFuture<>(),
                new AtomicBoolean());
        pending.add(item);
        return item;
    }

    public List<Pending> pending() {
        pending.removeIf(item -> item.decision().isDone());
        return List.copyOf(pending);
    }

    public void cancel() {
        List<Pending> cancelled = List.copyOf(pending);
        pending.clear();
        cancelled.forEach(item -> item.decision().cancel(false));
    }
}
