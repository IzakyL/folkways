package io.github.izakyl.folkways.cookery.tools;

import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.CookeryRefusal;
import io.github.izakyl.folkways.cookery.KitchenHolding;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.support.Leaves;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

// Cancellation and reload recover a physical return, never another execution of the food operation.
final class ToolReturn implements Node {
    private final TaskTools service;
    private final KitchenTools.Lease lease;
    private final NodeSpec spec;
    private boolean complete;
    private boolean ended;

    ToolReturn(TaskTools service, KitchenTools.Lease lease, Body body) {
        this.service = service;
        this.lease = lease;
        spec = NodeSpec.of(lease.id(), CookeryContent.ID, WorkSite.on(body.mob()), Stances.WHEREVER,
            new Workload.Custom())
            .effort(WorkEffort.NONE).leaves(Leaves.room(1)).doing(CookeryContent.PREPARING,
                lease.request().tools()).done();
    }

    public NodeSpec spec() { return spec; }

    public Optional<UUID> worker() { return Optional.of(lease.resident()); }

    @Override
    public WorkHolding acquire(HoldingContext context) {
        return new ReturnHolding();
    }

    private final class ReturnHolding extends KitchenHolding {

        ReturnHolding() {
            super(ToolReturn.this::operation, () -> complete, ToolReturn.this::commit, ignored -> {});
        }

        @Override
        public void resumed(HoldingContext context) {
            WorkAction pending = operation;
            super.resumed(context);
            operation = pending;
        }

        @Override
        protected boolean ready(HoldingContext context, WorkAction operation) {
            return ((ToolAction.Return) operation).ready(context.level());
        }

        @Override
        protected HoldingStep stepCompleted(HoldingContext context, WorkAction operation, HoldingStep step) {
            ToolAction.Return returning = (ToolAction.Return) operation;
            complete = returning.moved || returning.missing;
            return new HoldingStep.Running();
        }
    }

    private ToolAction.Return operation(ServerLevel level, Worker who) {
        if (complete) {
            return null;
        }
        if (service.stock.waitingForResident(lease)) { return null; }
        var body = service.stock.body(lease.resident());
        if (body.isPresent() && !LoanClothing.recover(service, lease, body.get())) { return null; }
        if (service.stock.waitingForResident(lease)) { return null; }
        int slot = service.carriedSlot(who.pack(), lease, service.tool(lease).orElse(ItemStack.EMPTY));
        if (service.stock.waitingForResident(lease)) { return null; }
        if (slot < 0) {
            service.missing(lease);
            complete = true;
            return null;
        }
        return service.destination(level, lease, who.pack().getItem(slot))
            .map(target -> new ToolAction.Return(service, lease, target))
                .orElse(null);
    }

    public Outcome commit(ServerLevel level, Worker who) {
        return complete ? Outcome.done() : Outcome.failed(CookeryRefusal.STATION_BUSY);
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        if (ended) {
            return;
        }
        ended = true;
        if (complete) {
            io.github.izakyl.folkways.core.resident.api.TaskTools.release(lease.id());
        }
    }
}
