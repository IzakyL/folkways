package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

final class GleanNode extends PenNode {

    GleanNode(Chore chore) {
        super(PenNode.declare(chore, Herd.Job.GLEAN).doing(PastureContent.GLEANING).done(), chore);
    }

    @Override
    public boolean ready(ServerLevel level) {
        return lying(level) != null;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        ItemEntity lying = lying(level);
        if (lying == null) {
            return refuse(PastureRefusal.NOTHING_TO_TAKE);
        }
        ItemStack taken = lying.getItem().copy();
        lying.discard();
        return counted(level, new Outcome.Done(List.of(WorkNoise.stowed()), List.of(taken),
            Optional.of(new Xp(PastureContent.trade(), 1))));
    }

    private ItemEntity lying(ServerLevel level) {
        Entity found = level.getEntity(subject());
        return found instanceof ItemEntity drop && drop.isAlive() && !drop.getItem().isEmpty()
            ? drop
            : null;
    }
}
