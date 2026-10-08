package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.Planner;
import io.github.izakyl.folkways.core.engine.plan.Solved;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;

final class Cycle {

    private static final long SLOW_PLAN_NANOS = 100_000_000L;

    private final Inbox inbox;

    private boolean planning;

    private long began;

    private volatile Runnable completed;

    private volatile boolean closed;

    Cycle(Inbox inbox) {
        this.inbox = inbox;
    }

    boolean busy() {
        return planning;
    }

    long busyFor(long now) {
        return planning && completed == null ? Math.max(0, now - began) : 0;
    }

    void begin(ServerLevel level, Executor onPlanThread, Planner planner, Known known, long tick,
            BiConsumer<List<Message>, Solved> apply) {
        if (closed) {
            return;
        }
        List<Message> batch = inbox.hand();
        planning = true;
        began = Util.getNanos();
        long queuedAt = began;
        onPlanThread.execute(() -> {
            long started = Util.getNanos();
            Solved solved;
            try {
                solved = planner.handle(known, batch, tick);
            } catch (RuntimeException | Error broken) {
                Labor.LOGGER.error("planning failed; its {} messages go back on the queue",
                    batch.size(), broken);
                complete(level, () -> {
                    inbox.giveBack();
                    planning = false;
                });
                return;
            }
            long finished = Util.getNanos();
            if (FolkwaysConfig.logPlanHeartbeat() && finished - queuedAt >= SLOW_PLAN_NANOS) {
                Planner.Timing timing = planner.timing();
                Labor.LOGGER.info("solve timing tick={} queue_us={} messages_us={} weave_us={}"
                        + " schedule_us={} total_us={} bodies={} messages={} nodes={}",
                    tick, (started - queuedAt) / 1000, timing.messageNanos() / 1000,
                    timing.weaveNanos() / 1000, timing.scheduleNanos() / 1000,
                    (finished - queuedAt) / 1000, known.hands().size(), batch.size(),
                    solved.weave().vertices().size());
            }
            complete(level, () -> {
                try {
                    inbox.digested();
                    apply.accept(batch, solved);
                } finally {
                    planning = false;
                }
            });
        });
    }

    private void complete(ServerLevel level, Runnable action) {
        if (closed) {
            return;
        }
        completed = action;
        if (level.getServer().isSameThread()) {
            applyReady(level);
        }
    }

    void applyReady(ServerLevel level) {
        if (closed) {
            completed = null;
            return;
        }
        if (!level.getServer().tickRateManager().runsNormally()) {
            return;
        }
        Runnable action = completed;
        if (action != null) {
            completed = null;
            action.run();
        }
    }

    void close() {
        closed = true;
        completed = null;
    }
}
