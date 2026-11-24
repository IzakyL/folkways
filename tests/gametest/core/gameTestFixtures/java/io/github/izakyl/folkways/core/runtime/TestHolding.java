package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.WorkHolding;

// The same lifecycle observations apply to a protocol probe and to real private actions.
public final class TestHolding implements WorkHolding {
    private final WorkHolding delegate;
    private boolean closing;
    public int requests;
    public int hard;
    public int suspensions;
    public int resumptions;
    public int ticks;
    public boolean cooperates = true;
    public Runnable onHard;
    public SoftPreempt request;
    public Disposition result;
    public PreemptReason reason;

    public TestHolding() {
        this(null);
    }

    public TestHolding(WorkHolding delegate) {
        this.delegate = delegate;
    }

    public HoldingStep tick(HoldingContext context) {
        ticks++;
        if (closing && !cooperates) {
            return new HoldingStep.Running();
        }
        return delegate == null
            ? closing ? new HoldingStep.Yielded() : new HoldingStep.Running()
            : delegate.tick(context);
    }

    public void requestSoftPreempt(HoldingContext context, SoftPreempt request) {
        requests++;
        this.request = request;
        closing = true;
        if (delegate != null) {
            delegate.requestSoftPreempt(context, request);
        }
    }

    public void hardRelease(HoldingContext context, Disposition result, PreemptReason reason) {
        hard++;
        this.result = result;
        this.reason = reason;
        if (delegate != null) {
            delegate.hardRelease(context, result, reason);
        }
        if (onHard != null) {
            onHard.run();
        }
    }

    public void suspended(HoldingContext context, PreemptReason reason) {
        suspensions++;
        if (delegate != null) {
            delegate.suspended(context, reason);
        }
    }

    public void resumed(HoldingContext context) {
        resumptions++;
        closing = false;
        if (delegate != null) {
            delegate.resumed(context);
        }
    }
}
