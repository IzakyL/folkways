package io.github.izakyl.folkways.cookery;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.ActionHandle;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;

// Body control shared by kitchen operations; each station owns its native batch and step selection.
public class KitchenHolding implements WorkHolding {
    private final BiFunction<ServerLevel, Worker, ? extends WorkAction> next;
    private final BooleanSupplier complete;
    private final BiFunction<ServerLevel, Worker, Outcome> outcome;
    private final Consumer<RefusalKind> failed;
    protected WorkAction operation;
    protected ActionHandle action;
    protected boolean closing;
    private boolean released;

    public KitchenHolding(BiFunction<ServerLevel, Worker, ? extends WorkAction> next, BooleanSupplier complete,
            BiFunction<ServerLevel, Worker, Outcome> outcome, Consumer<RefusalKind> failed) {
        this.next = next;
        this.complete = complete;
        this.outcome = outcome;
        this.failed = failed;
    }

    @Override
    public HoldingStep tick(HoldingContext context) {
        if (closing) {
            return advance(context);
        }
        if (operation == null) {
            operation = next.apply(context.level(), context.worker());
            if (operation == null) {
                return idle(context);
            }
        }
        if (!ready(context, operation)) {
            return new HoldingStep.Running();
        }
        if (action == null) {
            start(context, operation);
        }
        HoldingStep step = advance(context);
        if (step instanceof HoldingStep.Done || step instanceof HoldingStep.Failed) {
            WorkAction done = operation;
            operation = null;
            return stepCompleted(context, done, step);
        }
        return new HoldingStep.Running();
    }

    protected boolean ready(HoldingContext context, WorkAction operation) {
        return true;
    }

    protected HoldingStep idle(HoldingContext context) {
        return complete.getAsBoolean()
            ? finish(context, outcome.apply(context.level(), context.worker())) : new HoldingStep.Running();
    }

    protected HoldingStep stepCompleted(HoldingContext context, WorkAction operation, HoldingStep step) {
        if (step instanceof HoldingStep.Failed failure) {
            failed.accept(failure.why());
            return step;
        }
        return new HoldingStep.Running();
    }

    protected void start(HoldingContext context, WorkAction operation) {
        action = context.action(operation);
    }

    protected HoldingStep advance(HoldingContext context) {
        if (closing || released) {
            return action == null ? new HoldingStep.Yielded() : action.tick(context);
        }
        HoldingStep step = action.tick(context);
        if (step instanceof HoldingStep.Done || step instanceof HoldingStep.Failed) {
            action.reportEffort(context.body());
            action = null;
        }
        return step;
    }

    protected HoldingStep finish(HoldingContext context, Outcome outcome) {
        context.body().clearDoing();
        context.body().clearHandPresentation();
        return outcome instanceof Outcome.Failed failed
            ? new HoldingStep.Failed(failed.why())
            : new HoldingStep.Done();
    }

    @Override
    public void requestSoftPreempt(HoldingContext context, SoftPreempt request) {
        closing = true;
        if (action != null) {
            action.requestSoftPreempt(context, request);
        }
    }

    @Override
    public void hardRelease(HoldingContext context, Disposition result, PreemptReason reason) {
        if (released) {
            return;
        }
        released = true;
        closing = true;
        if (action != null) {
            action.hardRelease(context, result, reason);
            action.reportEffort(context.body());
            action = null;
        }
        context.body().clearDoing();
        context.body().clearHandPresentation();
    }

    @Override
    public void suspended(HoldingContext context, PreemptReason reason) {
    }

    @Override
    public void resumed(HoldingContext context) {
        released = false;
        closing = false;
        // Uncommitted wind-ups are selected again against the current native station state.
        action = null;
        operation = null;
    }
}
