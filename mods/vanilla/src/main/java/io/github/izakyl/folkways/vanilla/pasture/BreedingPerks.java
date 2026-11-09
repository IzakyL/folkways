package io.github.izakyl.folkways.vanilla.pasture;

import io.github.izakyl.folkways.core.base.persist.Required;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;

final class BreedingPerks {
    private static final String MARK = "folkways_breeding";
    static final String NURTURED = "folkways_nurtured";

    static void fed(ServerLevel level, Worker who, Animal animal, Animal mate, Pen pen) {
        CompoundTag mark = new CompoundTag();
        mark.putDouble("chance", who.effects().value(PastureContent.TWINS));
        mark.putUUID("mate", mate.getUUID());
        mark.putLong("expires", level.getGameTime() + 600);
        mark.putInt("target", pen.target());
        mark.putInt("min_y", pen.minY());
        mark.putInt("max_y", pen.maxY() + 4);
        // Save world-space footprint; the birth callback needs no live colony reference.
        mark.putLongArray("floor", pen.floor().stream().mapToLong(pos ->
            pen.anchor().at(pos).block(level).asLong()).toArray());
        animal.getPersistentData().put(MARK, mark);
    }

    static void born(BabyEntitySpawnEvent event) {
        if (!(event.getParentA() instanceof Animal first) || !(event.getParentB() instanceof Animal second)
                || !(first.level() instanceof ServerLevel level) || event.getChild() == null) { return; }
        CompoundTag mark = first.getPersistentData().getCompound(MARK);
        if (!valid(mark, level, second)) {
            mark = second.getPersistentData().getCompound(MARK);
            if (!valid(mark, level, first)) { return; }
        }
        first.getPersistentData().remove(MARK);
        second.getPersistentData().remove(MARK);
        tamed(event.getChild(), first);
        Set<Long> footprint = new HashSet<>();
        AABB bounds = null;
        for (long packed : Required.longArray(mark, "floor")) {
            BlockPos pos = BlockPos.of(packed);
            footprint.add(BlockPos.asLong(pos.getX(), 0, pos.getZ()));
            AABB cell = new AABB(pos).inflate(0, 4, 0);
            bounds = bounds == null ? cell : bounds.minmax(cell);
        }
        if (bounds == null) { return; }
        int minY = Required.integer(mark, "min_y"), maxY = Required.integer(mark, "max_y");
        long living = level.getEntitiesOfClass(Animal.class, bounds, animal ->
            animal.isAlive() && animal.getType() == first.getType()
                && animal.getY() >= minY && animal.getY() <= maxY
                && footprint.contains(BlockPos.asLong(animal.blockPosition().getX(), 0,
                    animal.blockPosition().getZ()))).size();
        // The ordinary child has not entered the level yet and also needs its place.
        if (living + 1 >= Required.integer(mark, "target") || level.random.nextDouble() >= Required.doubleValue(mark, "chance")) { return; }
        var extra = first.getBreedOffspring(level, second);
        if (extra != null) {
            tamed(extra, first);
            extra.setAge(-24000);
            extra.moveTo(first.position());
            level.addFreshEntity(extra);
        }
    }

    // A foal bred in a pen is born tame to its parent's owner, so the pen's horses keep breeding and can be put to
    // carts without a player taming each one.
    private static void tamed(AgeableMob child, Animal parent) {
        if (child instanceof AbstractHorse foal && parent instanceof AbstractHorse horse && horse.isTamed()) {
            foal.setTamed(true);
            foal.setOwnerUUID(horse.getOwnerUUID());
        }
    }

    private static boolean valid(CompoundTag mark, ServerLevel level, Animal mate) {
        return mark.hasUUID("mate") && Required.uuid(mark, "mate").equals(mate.getUUID())
            && Required.longValue(mark, "expires") >= level.getGameTime();
    }
}
