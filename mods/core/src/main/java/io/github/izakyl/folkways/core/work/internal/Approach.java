package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.support.Walking;

// The planner brings the body to its assigned stance before the node takes control.
final class Approach implements WorkHolding {
    private final Walking walking;
    private HoldingStep failure;
    private boolean arrived;
    private boolean released;
    private boolean soft;

    Approach(Node node, Placement placement, HoldingContext context) {
        walking = new Walking(node, placement);
        setOff(context);
    }

    boolean arrived() {
        return arrived;
    }

    boolean released() {
        return released;
    }

    boolean travelling() {
        return walking.travelling();
    }

    @Override
    public HoldingStep tick(HoldingContext context) {
        if (failure != null) {
            return failure;
        }
        if (soft) {
            release(context);
        }
        if (released || arrived) {
            return new HoldingStep.Yielded();
        }
        observe(walking.tick(context, context.actions()));
        return failure != null ? failure : arrived ? new HoldingStep.Yielded() : new HoldingStep.Running();
    }

    @Override
    public void requestSoftPreempt(HoldingContext context, SoftPreempt request) {
        soft = true;
    }

    @Override
    public void hardRelease(HoldingContext context, Disposition result, PreemptReason reason) {
        release(context);
    }

    @Override
    public void suspended(HoldingContext context, PreemptReason reason) {
    }

    @Override
    public void resumed(HoldingContext context) {
        released = false;
        soft = false;
        if (!arrived) {
            setOff(context);
        }
    }

    private void setOff(HoldingContext context) {
        observe(walking.start(context, context.actions()));
    }

    private void observe(HoldingStep step) {
        if (step instanceof HoldingStep.Done) {
            arrived = true;
        } else if (step instanceof HoldingStep.Failed) {
            failure = step;
        }
    }

    private void release(HoldingContext context) {
        if (released) {
            return;
        }
        released = true;
        walking.release(context.body());
        context.body().clearDoing();
    }
}
