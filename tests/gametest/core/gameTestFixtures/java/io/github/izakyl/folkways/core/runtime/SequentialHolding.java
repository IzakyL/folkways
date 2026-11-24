package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.work.api.ActionHandle;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import java.util.List;
import java.util.function.Consumer;

// A test-owned sequence: private actions never enter the personal phase graph.
public final class SequentialHolding implements WorkHolding {
    private final List<WorkAction> steps;
    private final Consumer<WorkAction> completed;
    private final Runnable released;
    private ActionHandle action;
    private int at;
    private boolean closing;
    private boolean closed;

    public SequentialHolding(List<WorkAction> steps, Consumer<WorkAction> completed) {
        this.steps = List.copyOf(steps);
        this.completed = completed;
        released = () -> {};
    }

    public SequentialHolding(HoldingContext context, List<WorkAction> steps, Runnable released) {
        this.steps = List.copyOf(steps);
        completed = action -> {};
        this.released = released;
        action = context.action(steps.getFirst());
    }

    public HoldingStep tick(HoldingContext context) {
        if (action == null) {
            if (closing) {
                return new HoldingStep.Yielded();
            }
            if (at == steps.size()) {
                return new HoldingStep.Done();
            }
            action = context.action(steps.get(at));
        }
        HoldingStep step = action.tick(context);
        if (step instanceof HoldingStep.Done) {
            completed.accept(steps.get(at++));
            action = null;
            step = at == steps.size() ? new HoldingStep.Done() : new HoldingStep.Running();
        }
        if (step instanceof HoldingStep.Done || step instanceof HoldingStep.Yielded
                || step instanceof HoldingStep.Failed) {
            close();
        }
        return step;
    }

    public void requestSoftPreempt(HoldingContext context, SoftPreempt request) {
        closing = true;
        if (action != null) {
            action.requestSoftPreempt(context, request);
        }
    }

    public void hardRelease(HoldingContext context, Disposition result, PreemptReason reason) {
        if (action != null) {
            action.hardRelease(context, result, reason);
        }
        close();
    }

    private void close() {
        if (!closed) {
            closed = true;
            released.run();
        }
    }

    public void suspended(HoldingContext context, PreemptReason reason) {
        if (action != null) {
            action.suspended(context, reason);
        }
    }

    public void resumed(HoldingContext context) {
        closing = false;
        closed = false;
        if (action != null) {
            action.resumed(context);
        }
    }
}
