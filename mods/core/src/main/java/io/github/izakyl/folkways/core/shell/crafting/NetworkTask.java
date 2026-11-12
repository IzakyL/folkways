package io.github.izakyl.folkways.core.shell.crafting;

import java.util.Optional;
import java.util.UUID;

/** The native job currently commissioning colony work; cancellation never transfers ownership back to it. */
public final class NetworkTask implements AutoCloseable {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private final UUID previous;

    public NetworkTask(UUID task) {
        previous = CURRENT.get();
        CURRENT.set(task);
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
