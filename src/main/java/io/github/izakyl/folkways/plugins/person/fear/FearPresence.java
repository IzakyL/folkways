package io.github.izakyl.folkways.plugins.person.fear;

import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

final class FearPresence {

    static final ResourceLocation FLEE = ResourceLocation.fromNamespaceAndPath("folkways", "flee");

    public List<Urge> urges(Worker who, ColonyView colony) {
        Optional<BlockPos> away = away(who);
        if (away.isEmpty()) {
            return List.of();
        }
        ServerLevel level = colony.level();
        BlockPos to = away.get();
        return List.of(new Urge(FLEE, Urge.DIRE, true,
            () -> new FleeAction(level, who.resident().id(), to)));
    }

    private static Optional<BlockPos> away(Worker who) {
        Mob body = who.body();
        DamageSource hurt = body.getLastDamageSource();
        if (hurt == null || !(hurt.is(DamageTypeTags.PANIC_CAUSES) || hurt.is(DamageTypeTags.IS_FIRE))
                || body.isPassenger()) {
            return Optional.empty();
        }
        Entity from = hurt.getEntity();
        return Fleeing.awayFrom(body, Optional.ofNullable(from).map(Entity::position));
    }
}
