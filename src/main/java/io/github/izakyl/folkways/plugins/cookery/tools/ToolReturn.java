package io.github.izakyl.folkways.plugins.cookery.tools;

import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.CookeryRefusal;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

// Cancellation and reload recover a physical return, never another execution of the food operation.
final class ToolReturn implements Node, Workload.Within {
    private final TaskTools service;
    private final KitchenTools.Lease lease;
    private final NodeSpec spec;
    private boolean complete;

    ToolReturn(TaskTools service, KitchenTools.Lease lease, Body body) {
        this.service = service;
        this.lease = lease;
        spec = NodeSpec.of(lease.id(), CookeryContent.ID, WorkSite.on(body.mob()), Stances.WHEREVER, this)
            .effort(WorkEffort.NONE).leaves(Leaves.room(1)).doing(CookeryContent.PREPARING, lease.request().tools()).done();
    }

    public NodeSpec spec() { return spec; }
    public Optional<UUID> worker() { return Optional.of(lease.resident()); }

    @Override
    public Turn next(ServerLevel level, Worker who) {
        if (complete) {
            return LEAVE;
        }
        int slot = service.carriedSlot(who.pack(), lease, service.tool(lease).orElse(ItemStack.EMPTY));
        if (slot < 0) {
            service.lost(lease);
            complete = true;
            return LEAVE;
        }
        return service.destination(level, lease, who.pack().getItem(slot))
            .<Turn>map(target -> new Turn.Piece(new ToolAction.Return(service, lease, target))).orElse(WAIT);
    }

    @Override
    public void ended(ServerLevel level, Node piece, Ending how) {
        if (piece instanceof ToolAction.Return returned && (returned.moved || returned.missing)) {
            complete = true;
        }
    }

    public Outcome commit(ServerLevel level, Worker who) {
        return complete ? Outcome.done() : Outcome.failed(CookeryRefusal.STATION_BUSY);
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        if (complete) {
            io.github.izakyl.folkways.core.api.resident.body.TaskTools.release(lease.id());
        }
    }
}
