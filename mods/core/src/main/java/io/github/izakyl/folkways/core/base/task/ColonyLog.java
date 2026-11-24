package io.github.izakyl.folkways.core.base.task;

import java.util.Objects;
import java.util.UUID;
import org.apache.logging.log4j.ThreadContext;

public final class ColonyLog implements AutoCloseable {

    public static final String KEY = "folkways.colony";

    private final String previous;

    private ColonyLog(String colony) {
        previous = ThreadContext.get(KEY);
        if (!Objects.equals(previous, colony)) {
            restore(colony);
        }
    }

    public static ColonyLog open(UUID colony) {
        return new ColonyLog(colony == null ? null : colony.toString());
    }

    public static Runnable capture(Runnable task) {
        String colony = ThreadContext.get(KEY);
        return () -> {
            try (var ignored = new ColonyLog(colony)) {
                task.run();
            }
        };
    }

    private static void restore(String colony) {
        if (colony == null) {
            ThreadContext.remove(KEY);
        } else {
            ThreadContext.put(KEY, colony);
        }
    }

    @Override
    public void close() {
        if (!Objects.equals(previous, ThreadContext.get(KEY))) {
            restore(previous);
        }
    }
}
