package io.github.izakyl.folkways.cookery.tools;

import io.github.izakyl.folkways.cookery.KitchenHolding;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.Settlement;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

// Equipment policy stays with the wrapper; this holding only sequences preparation, work and return.
final class EquipmentHolding extends KitchenHolding {
    private final Node original;
    private final BooleanSupplier prepared;
    private final BooleanSupplier workDone;
    private final Function<HoldingContext, HoldingContext> equipped;
    private final Completion finished;
    private final BiConsumer<ToolAction, Ending> completed;
    private WorkHolding work;

    interface Completion {
        void finish(HoldingContext context, HoldingStep step, Runnable release);
    }

    EquipmentHolding(Node original, BooleanSupplier prepared, BooleanSupplier workDone,
            Function<HoldingContext, HoldingContext> equipped, Completion finished,
            BiFunction<ServerLevel, Worker, ToolAction> next, BooleanSupplier complete,
            BiFunction<ServerLevel, Worker, Outcome> outcome, BiConsumer<ToolAction, Ending> completed) {
        super(next, complete, outcome, ignored -> {});
        this.original = original;
        this.prepared = prepared;
        this.workDone = workDone;
        this.equipped = equipped;
        this.finished = finished;
        this.completed = completed;
    }

    static HoldingContext context(HoldingContext context, Worker worker, Function<WorkAction, WorkAction> wrap) {
        Settlement settlement = new Settlement() {
            @Override
            public Outcome commit(WorkAction operation) {
                return context.settlement().commit(wrap.apply(operation));
            }

            @Override
            public void stow(List<ItemStack> made) {
                context.settlement().stow(made);
            }
        };
        return new HoldingContext(context.level(), context.body(), worker, context.actions(),
            settlement, context.holdings(), context.joining());
    }

    @Override
    public HoldingStep tick(HoldingContext context) {
        if (closing && active()) {
            HoldingStep step = work.tick(equipped.apply(context));
            if (step instanceof HoldingStep.Done || step instanceof HoldingStep.Failed) {
                finished.finish(context, step, () -> {});
            }
            return step instanceof HoldingStep.Running ? step : new HoldingStep.Yielded();
        }
        return super.tick(context);
    }

    @Override
    protected boolean ready(HoldingContext context, WorkAction operation) {
        return ((ToolAction) operation).ready(context.level());
    }

    @Override
    protected HoldingStep stepCompleted(HoldingContext context, WorkAction operation, HoldingStep step) {
        completed.accept((ToolAction) operation, step instanceof HoldingStep.Failed failure
            ? new Ending.Failed(original.id(), failure.why()) : Ending.DONE);
        return new HoldingStep.Running();
    }

    @Override
    protected HoldingStep idle(HoldingContext context) {
        if (prepared.getAsBoolean() && !workDone.getAsBoolean()) {
            HoldingContext actual = equipped.apply(context);
            if (work == null) {
                work = original.acquire(actual);
            }
            HoldingStep step = work.tick(actual);
            if (step instanceof HoldingStep.Done || step instanceof HoldingStep.Failed) {
                // Mark completion before release, preserving wrapper state during reentrant callbacks.
                finished.finish(context, step, () -> work.hardRelease(actual, Disposition.END, PreemptReason.OTHER));
            }
        }
        return super.idle(context);
    }

    private boolean active() {
        return work != null && !workDone.getAsBoolean();
    }

    @Override
    public void requestSoftPreempt(HoldingContext context, SoftPreempt request) {
        super.requestSoftPreempt(context, request);
        if (active()) {
            work.requestSoftPreempt(equipped.apply(context), request);
        }
    }

    @Override
    public void hardRelease(HoldingContext context, Disposition result, PreemptReason reason) {
        if (active()) {
            work.hardRelease(equipped.apply(context), result, reason);
        }
        super.hardRelease(context, result, reason);
    }

    @Override
    public void suspended(HoldingContext context, PreemptReason reason) {
        if (active()) {
            work.suspended(equipped.apply(context), reason);
        }
    }

    @Override
    public void resumed(HoldingContext context) {
        WorkAction pending = operation;
        super.resumed(context);
        operation = pending;
        if (active()) {
            work.resumed(equipped.apply(context));
        }
    }
}
