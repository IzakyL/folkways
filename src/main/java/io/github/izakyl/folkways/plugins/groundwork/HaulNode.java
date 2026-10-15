package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

// A stack lying loose in a haul zone, picked up and carried off to the colony's stores.
final class HaulNode extends GroundNode {

    private final UUID lying;

    HaulNode(ServerLevel level, Chores.Chore chore) {
        super(GroundNode.declare(chore, GroundworkContent.hauling(),
                Workload.Once.of(GroundworkContent.CELL_TICKS, GroundworkContent::haulingHaste))
            .leaves(leaves(level, ((WorkSite.AtEntity) chore.job().site()).entity()))
            .doing(GroundworkContent.PICKING_UP).done(), chore);
        this.lying = ((WorkSite.AtEntity) chore.job().site()).entity();
    }

    // The stack as it lies now, or a cell for it when it cannot be seen.
    private static List<Amount> leaves(ServerLevel level, UUID lying) {
        return level.getEntity(lying) instanceof ItemEntity drop
            ? Leaves.ofStacks(List.of(drop.getItem()))
            : Leaves.room(1);
    }

    @Override
    public boolean ready(ServerLevel level) {
        return lying(level) != null;
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job job) {
        ItemEntity drop = lying(level);
        if (drop == null) {
            return refuse(GroundRefusal.NOTHING_TO_WORK);
        }
        ItemStack taken = drop.getItem().copy();
        drop.discard();
        return new Outcome.Done(List.of(WorkNoise.stowed()), List.of(taken),
            Optional.of(new Xp(GroundworkContent.hauling(), 1)));
    }

    private ItemEntity lying(ServerLevel level) {
        Entity found = level.getEntity(lying);
        return found instanceof ItemEntity drop && drop.isAlive() && !drop.getItem().isEmpty() ? drop : null;
    }
}
