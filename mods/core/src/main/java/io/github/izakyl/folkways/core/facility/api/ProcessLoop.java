package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.InProgress;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

/**
 * The processing loop of the routine library: put the input in, start the block if it needs starting, wait for its
 * output, take the output out. One resident works one loop in one piece of work, and the loop picks the next step
 * each time the worker is free; what each step does to the block, and when the block lets it, is the caller's.
 *
 * <p>A loop resumes from visible input or from an adapter's reading of an internal batch. An internal batch confirms
 * that loading and starting already happened, so the resumed loop can wait or operate until output is available.
 *
 * <p>The loop judges nothing about what is processed. It holds only where the work has got to, and is used on the
 * server thread.
 */
public final class ProcessLoop {

    /** What the block in a loop does at each step. */
    public interface Steps {

        /** Why the work cannot go on, judged before every step; the loop then fails with it. */
        Optional<RefusalKind> refusal(ServerLevel level);

        /** Whether the work may touch the block now, such as its turn at a shared block; otherwise it waits. */
        default boolean turn(ServerLevel level) {
            return true;
        }

        /** Whether the input may go in now (the block is hot, the weather fits); otherwise the loop waits. */
        default boolean loadable(ServerLevel level) {
            return true;
        }

        /** Puts input in, possibly in several steps. The final deposit calls {@link #loaded}. */
        WorkAction load(ServerLevel level, Worker who);

        /** Whether the block needs starting once loaded. */
        default boolean starts() {
            return false;
        }

        /** Starts the block; its commit calls {@link #started}. Asked only when {@link #starts} says so. */
        default WorkAction start(ServerLevel level, Worker who) {
            throw new UnsupportedOperationException("this loop has no start step");
        }

        /** The matching internal batch, even when neither port exposes its contents. */
        default Optional<? extends InProgress<?>> inProgress(ServerLevel level) {
            return Optional.empty();
        }

        /** A repeated or continuous intervention. Returns null whenever the machine needs no operator action. */
        default WorkAction operate(ServerLevel level, Worker who) {
            return null;
        }

        /** Whether the output is there to take. */
        boolean outputReady(ServerLevel level);

        /** Takes the output out; its commit calls {@link #finished}. */
        WorkAction take(ServerLevel level, Worker who);
    }

    private final Steps steps;
    private boolean loaded;
    private boolean started;
    private boolean finished;
    private RefusalKind failed;

    /**
     * A loop over these steps; `alreadyLoaded` when the block already holds this loop's input. A resumed loop repeats
     * the start step unless the adapter reports that the machine is already processing.
     */
    public ProcessLoop(Steps steps, boolean alreadyLoaded) {
        this.steps = Objects.requireNonNull(steps, "steps");
        loaded = alreadyLoaded;
    }

    /**
     * The next step, or null when there is none to take now: waiting for the turn, for the block to let the input
     * in, or for the output; or because the loop is over.
     */
    public WorkAction next(ServerLevel level, Worker who) {
        steps.refusal(level).ifPresent(this::fail);
        if (over()) {
            return null;
        }
        if (!steps.turn(level)) {
            return null;
        }
        boolean processing = steps.inProgress(level).isPresent();
        if (processing) {
            loaded = true;
            started = true;
        }
        if (!loaded) {
            return steps.loadable(level) ? steps.load(level, who) : null;
        }
        if (!started && steps.starts()) {
            return steps.start(level, who);
        }
        WorkAction intervention = steps.operate(level, who);
        if (intervention != null) {
            return intervention;
        }
        return !processing && steps.outputReady(level) ? steps.take(level, who) : null;
    }

    public void loaded() {
        loaded = true;
    }

    public void started() {
        started = true;
    }

    public void finished() {
        finished = true;
    }

    /** Ends the loop failed; a later reason replaces an earlier one, so the work reports the latest. */
    public void fail(RefusalKind why) {
        failed = Objects.requireNonNull(why, "why");
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isFinished() {
        return finished;
    }

    public Optional<RefusalKind> failure() {
        return Optional.ofNullable(failed);
    }

    /** Finished or failed. */
    public boolean over() {
        return finished || failed != null;
    }
}
