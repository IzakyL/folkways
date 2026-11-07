package io.github.izakyl.folkways.core.work.api;

public interface WorkHolding {
    // Steps requiring a body or world access must check their own prerequisites and wait when unavailable.
    // A terminal or yielded step leaves body control clean for the caller.
    HoldingStep tick(HoldingContext context);

    // Finish or clean up existing steps without starting new production; the engine enforces the deadline.
    void requestSoftPreempt(HoldingContext context, SoftPreempt request);

    // Release body control synchronously and idempotently for this generation, without releasing node resources.
    void hardRelease(HoldingContext context, Disposition result, PreemptReason reason);

    // Called once on entering suspension, even if the work will later end without resuming.
    void suspended(HoldingContext context, PreemptReason reason);

    // Called after reacquiring the body and before advancing any steps.
    void resumed(HoldingContext context);
}
