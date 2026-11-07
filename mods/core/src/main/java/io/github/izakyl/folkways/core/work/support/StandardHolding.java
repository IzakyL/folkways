package io.github.izakyl.folkways.core.work.support;

import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.WorkHolding;

/** Standard node execution uses the same action executor as private holding steps. */
public final class StandardHolding implements WorkHolding {
    private final ActionExecutor action;
    private boolean reported;
    private SoftPreempt request;

    public StandardHolding(Node node, Placement placement, boolean joining,
            HoldingContext context) {
        action = new ActionExecutor(context, node, placement, joining);
    }

    private void reportEffort(Body body) {
        if (!reported) {
            action.reportEffort(body);
            reported = true;
        }
    }

    @Override
    public HoldingStep tick(HoldingContext context) {
        HoldingStep step = action.tick(context);
        if (step instanceof HoldingStep.Done || step instanceof HoldingStep.Failed
                || step instanceof HoldingStep.Yielded && request != null && request.result() == Disposition.END) {
            reportEffort(context.body());
        }
        return step;
    }

    @Override
    public void requestSoftPreempt(HoldingContext context, SoftPreempt request) {
        this.request = request;
        action.requestSoftPreempt(context, request);
    }

    @Override
    public void hardRelease(HoldingContext context, Disposition result, PreemptReason reason) {
        action.hardRelease(context, result, reason);
        if (result == Disposition.END) {
            reportEffort(context.body());
        }
    }

    @Override
    public void suspended(HoldingContext context, PreemptReason reason) {
        action.suspended(context, reason);
    }

    @Override
    public void resumed(HoldingContext context) {
        reported = false;
        request = null;
        action.resumed(context);
    }
}
