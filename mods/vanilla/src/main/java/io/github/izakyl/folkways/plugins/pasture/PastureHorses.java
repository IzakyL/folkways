package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.ZoneView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.phys.AABB;

public final class PastureHorses {
    private PastureHorses() {
    }

    public static List<AbstractHorse> available(Colony colony, ServerLevel level) {
        List<AbstractHorse> available = new ArrayList<>();
        for (ZoneView zone : Fronts.of(colony).zonesIn(level)) {
            if (!zone.delegation().equals(PastureContent.PASTURE) || zone.cells().isEmpty()
                    || !zone.settings().choice(PastureContent.ANIMAL.key())
                        .equals(BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.HORSE))) {
                continue;
            }
            AABB bounds = null;
            for (BlockPos cell : zone.cells()) {
                AABB one = new AABB(cell).inflate(0, 3, 0);
                bounds = bounds == null ? one : bounds.minmax(one);
            }
            for (AbstractHorse horse : level.getEntitiesOfClass(AbstractHorse.class, bounds, horse ->
                    horse.getType() == EntityType.HORSE && horse.isAlive() && horse.isTamed() && !horse.isBaby()
                        && !horse.isVehicle() && !horse.isLeashed() && !horse.isPassenger()
                        && !horse.getTags().contains(PastureContent.CLAIMED)
                        && (zone.cells().contains(horse.blockPosition())
                            || zone.cells().contains(horse.blockPosition().below()))
                        && colony.claimOf(horse.getUUID()).isEmpty()
                        && Colonies.all(level.getServer()).stream()
                            .noneMatch(other -> other.claimOf(horse.getUUID()).isPresent()))) {
                if (!available.contains(horse)) {
                    available.add(horse);
                }
            }
        }
        return List.copyOf(available);
    }

    public static Optional<AbstractHorse> claim(Colony colony, ServerLevel level, ResourceLocation owner) {
        for (AbstractHorse horse : available(colony, level)) {
            if (colony.hold(owner, Claimed.Entity.of(horse)).isPresent()) {
                horse.addTag(PastureContent.CLAIMED);
                return Optional.of(horse);
            }
        }
        return Optional.empty();
    }

    public static void release(Colony colony, ResourceLocation owner, AbstractHorse horse) {
        var holding = colony.claimOf(horse.getUUID());
        if (holding.isPresent() && !holding.get().owner().equals(owner)) {
            return;
        }
        // Razing forgets the holdings before their owners close.
        horse.removeTag(PastureContent.CLAIMED);
        holding.ifPresent(held -> colony.release(held.id(), ClaimRelease.LET_GO));
    }
}
