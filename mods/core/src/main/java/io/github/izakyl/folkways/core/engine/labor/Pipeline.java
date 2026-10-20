package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.Planner;
import io.github.izakyl.folkways.core.engine.plan.Scheduler;
import io.github.izakyl.folkways.core.engine.plan.Tour;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;

/**
 * Planning as two stages, each on a thread of its own: the weave takes what the colony reported and grows the plan,
 * and hands what changed on; the schedule takes those changes, as many as came while it was busy, and lays routes.
 * Neither waits for the other: the weave goes on to what was reported since while the schedule lays the last
 * change out. What each stage made comes back to the server thread in the order it was made, a weave's change
 * always before any schedule laid for it. Run on the server thread itself, both stages finish before this returns.
 */
final class Pipeline {

    private static final long SLOW_NANOS = 100_000_000L;

    private final Inbox inbox;
    private final Planner planner;
    private final Scheduler scheduler;

    private final Queue<Runnable> made = new ConcurrentLinkedQueue<>();
    private final Queue<Scheduler.Input> laying = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduling = new AtomicBoolean();

    // The routes the schedule last laid, as the weave stage reads them.
    private volatile Map<UUID, Tour> tours = Map.of();

    private boolean weaving;
    private long weaveBegan;
    private volatile long scheduleBegan;
    private volatile boolean closed;
    // The schedule lane raises the gate before publishing its failure. Only a simulation tick lowers it;
    // new weave inputs wait behind it too. Failure counts belong to that lane; retry/now to the server thread.
    private volatile boolean retrying;
    private int scheduleFailures;
    private long now;
    private Retry retry;

    private record Retry(Executor thread, Consumer<Scheduler.Scheduled> laid, long at) { }

    Pipeline(Inbox inbox, Planner planner, Scheduler scheduler) {
        this.inbox = inbox;
        this.planner = planner;
        this.scheduler = scheduler;
    }

    boolean weaving() {
        return weaving;
    }

    // How long the stage that has been busy longest has been at it, nothing when both are idle.
    long busyFor(long now) {
        long weave = weaving ? now - weaveBegan : 0;
        long began = scheduleBegan;
        long schedule = began == 0 ? 0 : now - began;
        return Math.max(0, Math.max(weave, schedule));
    }

    // Hands everything reported so far to the weave, and what the weave makes of it on to the schedule.
    void weave(Executor weaveThread, Executor scheduleThread, Known known, long tick,
               BiConsumer<List<Message>, Planner.Woven> woven, Consumer<Scheduler.Scheduled> laid) {
        if (closed) {
            return;
        }
        List<Message> batch = inbox.hand();
        weaving = true;
        weaveBegan = Util.getNanos();
        long queuedAt = weaveBegan;
        weaveThread.execute(() -> {
            long started = Util.getNanos();
            Planner.Woven result;
            try {
                result = planner.handle(known, batch, tick, tours);
            } catch (RuntimeException | Error broken) {
                Labor.LOGGER.error("weaving failed; its {} messages go back on the queue", batch.size(), broken);
                made.add(() -> {
                    inbox.giveBack();
                    weaving = false;
                });
                return;
            }
            long finished = Util.getNanos();
            if (FolkwaysConfig.logPlanHeartbeat() && finished - queuedAt >= SLOW_NANOS) {
                Labor.LOGGER.info("weave timing tick={} queue_us={} messages_us={} weave_us={} total_us={}"
                        + " bodies={} messages={} nodes={} put={} removed={}",
                    tick, (started - queuedAt) / 1000, result.timing().messageNanos() / 1000,
                    result.timing().weaveNanos() / 1000, (finished - queuedAt) / 1000, known.hands().size(),
                    batch.size(), result.nodes(), result.delta().put().size(), result.delta().removed().size());
            }
            made.add(() -> {
                try {
                    inbox.digested();
                    woven.accept(batch, result);
                } finally {
                    weaving = false;
                }
            });
            lay(scheduleThread, new Scheduler.Input(result.delta(), result.crew(), result.ways(), result.retry(),
                tick), laid);
        });
    }

    // Time alone has brought work past the horizon nearer: the schedule looks again, at the plan it already has.
    void wake(Executor scheduleThread, long tick, Consumer<Scheduler.Scheduled> laid) {
        if (!closed) {
            lay(scheduleThread, Scheduler.Input.wake(tick), laid);
        }
    }

    private void lay(Executor scheduleThread, Scheduler.Input input, Consumer<Scheduler.Scheduled> laid) {
        if (closed) {
            return;
        }
        laying.add(input);
        if (!retrying && scheduling.compareAndSet(false, true)) {
            scheduleThread.execute(() -> drain(scheduleThread, laid));
        }
    }

    // Lays out every change handed on since the schedule last ran, in one schedule, until none is left. A change
    // handed on just as the schedule stopped finds it stopped, and starts it again.
    private void drain(Executor scheduleThread, Consumer<Scheduler.Scheduled> laid) {
        try {
            while (!closed) {
                List<Scheduler.Input> inputs = new ArrayList<>();
                for (Scheduler.Input input = laying.poll(); input != null; input = laying.poll()) {
                    inputs.add(input);
                }
                if (inputs.isEmpty()) {
                    break;
                }
                long began = Util.getNanos();
                scheduleBegan = began;
                Scheduler.Scheduled result;
                try {
                    result = scheduler.handle(inputs);
                } catch (RuntimeException | Error broken) {
                    Labor.LOGGER.error("scheduling failed over {} changes", inputs.size(), broken);
                    scheduleFailures = Math.min(scheduleFailures + 1, 6);
                    long delay = Math.min(20, 1L << (scheduleFailures - 1));
                    retrying = true;
                    made.add(() -> retry = new Retry(scheduleThread, laid, now + delay));
                    break;
                } finally {
                    scheduleBegan = 0;
                }
                scheduleFailures = 0;
                long finished = Util.getNanos();
                if (FolkwaysConfig.logPlanHeartbeat() && finished - began >= SLOW_NANOS) {
                    Labor.LOGGER.info("schedule timing tick={} changes={} apply_us={} schedule_us={} total_us={}"
                            + " nodes={} tours={} unassigned={}",
                        result.tick(), inputs.size(), result.timing().applyNanos() / 1000,
                        result.timing().scheduleNanos() / 1000, (finished - began) / 1000, scheduler.size(),
                        result.all().size(), result.unassigned().size());
                }
                tours = result.all();
                // The lane has no asynchronous child layouts. Successful publications remain ordered across
                // a synchronous Tours rebuild; close permanently rejects both ready and late publications.
                made.add(() -> laid.accept(result));
            }
        } finally {
            scheduling.set(false);
        }
        if (!closed && !retrying && !laying.isEmpty() && scheduling.compareAndSet(false, true)) {
            scheduleThread.execute(() -> drain(scheduleThread, laid));
        }
    }

    // Applies on the server thread whatever either stage has made, in the order they made it.
    void applyReady(ServerLevel level) {
        if (closed) {
            made.clear();
            return;
        }
        if (!level.getServer().tickRateManager().runsNormally()) {
            return;
        }
        now = level.getGameTime();
        for (Runnable next = made.poll(); next != null && !closed; next = made.poll()) {
            next.run();
        }
        if (!closed && retry != null && now >= retry.at()) {
            Retry due = retry;
            retry = null;
            retrying = false;
            // A failed layout has already accepted its inputs. A wake retries its complete current weave,
            // rather than resubmitting the input batch or the colony's original material demands.
            lay(due.thread(), Scheduler.Input.wake(now), due.laid());
        }
    }

    void close(Executor weaveThread, Executor scheduleThread) {
        closed = true;
        retry = null;
        made.clear();
        laying.clear();
        weaveThread.execute(planner::closed);
        scheduleThread.execute(scheduler::closed);
    }
}
