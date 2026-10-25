package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

final class CullNode extends AnimalNode {

    private static final double SPILL = 1.0D;

    // What a beast falls to is known only once it falls: meat and a hide, say, a cell each.
    private static final List<Amount> CARCASS = Leaves.room(2);
    private final Function<ServerLevel, Optional<Pen>> current;

    CullNode(Chore chore, Function<ServerLevel, Optional<Pen>> current) {
        super(PenNode.declare(chore, Herd.Job.CULL).leaves(CARCASS).doing(PastureContent.CULLING).done(), chore);
        this.current = current;
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Animal beast) {
        Pen pen = current.apply(level).orElse(null);
        if (pen == null || !pen.keeps(beast) || beast.isBaby() || pen.surplus(pen.stock(level)) == 0) {
            return refuse(PastureRefusal.HERD_CHANGED);
        }
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
        double chance = who.effects().value(PastureContent.CARCASS_YIELD);
        List<ItemStack> taken = new ArrayList<>();
        for (ItemEntity dropped : level.getEntitiesOfClass(ItemEntity.class, where)) {
            if (alreadyLying.contains(dropped.getId())) {
                continue;
            }
            ItemStack stack = dropped.getItem();
            if (stack.is(PastureContent.EXTRA_PRODUCTS)) {
                double extra = stack.getCount() * chance;
                stack.grow((int) extra + (level.random.nextDouble() < extra % 1 ? 1 : 0));
            }
            taken.add(stack);
            dropped.discard();
        }
        return new Outcome.Done(List.of(PastureNoise.STRUCK), List.copyOf(taken),
            Optional.of(new Xp(PastureContent.trade(), 2)));
    }
}
