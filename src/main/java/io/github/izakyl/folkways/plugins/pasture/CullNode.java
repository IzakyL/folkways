package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

final class CullNode extends AnimalNode {

    private static final double SPILL = 1.0D;

    CullNode(Chore chore) {
        super(PenNode.declare(chore, Herd.Job.CULL).doing(PastureContent.CULLING).done(), chore);
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Animal beast) {
        AABB where = beast.getBoundingBox().inflate(SPILL);
        Set<Integer> alreadyLying = new HashSet<>();
        for (ItemEntity older : level.getEntitiesOfClass(ItemEntity.class, where)) {
            alreadyLying.add(older.getId());
        }
        boolean fell = beast.hurt(level.damageSources().mobAttack(who.body()),
            beast.getMaxHealth() * 2.0F);
        if (!fell || beast.isAlive()) {
            return refuse(PastureRefusal.STILL_STANDING);
        }
        int extra = who.rankOf(PastureContent.BUTCHER);
        List<ItemStack> taken = new ArrayList<>();
        for (ItemEntity dropped : level.getEntitiesOfClass(ItemEntity.class, where)) {
            if (alreadyLying.contains(dropped.getId())) {
                continue;
            }
            ItemStack stack = dropped.getItem();
            stack.setCount(Math.min(stack.getMaxStackSize(), stack.getCount() + extra));
            taken.add(stack);
            dropped.discard();
        }
        return new Outcome.Done(List.of(PastureNoise.STRUCK), List.copyOf(taken),
            Optional.of(new Xp(PastureContent.trade(), 2)));
    }
}
