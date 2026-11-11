package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Reach the planned stance before asking the node for its own execution.
final class SuppliedHolding implements WorkHolding {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");
    private final Node node;
    private final Approach approach;
    private final boolean joining;
    private WorkHolding holding;
    private HoldingStep result;

    SuppliedHolding(Node node, Placement placement, HoldingContext context) {
        this.node = node;
        approach = new Approach(node, placement, context);
        this.joining = context.joining() && approach.arrived();
    }

    boolean travelling() {
        return holding == null && approach.travelling();
    }

    @Override
    public Optional<String> debugStage() {
        return holding == null ? Optional.of("approaching") : holding.debugStage();
    }

    @Override
    public HoldingStep tick(HoldingContext context) {
        if (result != null) {
            return result;
        }
        HoldingStep step = advance(context);
        if (step instanceof HoldingStep.Done || step instanceof HoldingStep.Failed) {
            result = step;
        }
        return step;
    }

    private HoldingStep advance(HoldingContext context) {
        if (holding == null) {
            HoldingStep step = approach.tick(context);
            if (!(step instanceof HoldingStep.Yielded) || !approach.arrived() || approach.released()) {
                return step;
            }
            try {
                context.body().locomotion().stand(context.body().mob(), node.mountedWork());
                holding = Objects.requireNonNull(node.acquire(new HoldingContext(context.level(), context.body(),
                    context.worker(), context.actions(), context.settlement(), context.holdings(), joining)));
            } catch (RuntimeException | LinkageError broken) {
                LOGGER.error("node {} threw acquiring its holding", node.id(), broken);
                return new HoldingStep.Failed(LaborRefusal.WORK_BROKE);
            }
        }
        try {
            return holding.tick(context);
        } catch (RuntimeException | LinkageError broken) {
            LOGGER.error("node {} threw advancing its holding", node.id(), broken);
            return new HoldingStep.Failed(LaborRefusal.WORK_BROKE);
        }
    }

    @Override
    public void requestSoftPreempt(HoldingContext context, SoftPreempt request) {
        execution().requestSoftPreempt(context, request);
    }

    @Override
    public void hardRelease(HoldingContext context, Disposition result, PreemptReason reason) {
        execution().hardRelease(context, result, reason);
    }

    @Override
    public void suspended(HoldingContext context, PreemptReason reason) {
        execution().suspended(context, reason);
    }

    @Override
    public void resumed(HoldingContext context) {
        execution().resumed(context);
    }

    private WorkHolding execution() {
        return holding == null ? approach : holding;
    }
}
