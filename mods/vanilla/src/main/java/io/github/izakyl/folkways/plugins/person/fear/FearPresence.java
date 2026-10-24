package io.github.izakyl.folkways.plugins.person.fear;

import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.combat.Threats;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

final class FearPresence {

    static final ResourceLocation FLEE = ResourceLocation.fromNamespaceAndPath("folkways", "flee");

    private record Escape(BlockPos to, long started) { }

    // A fresh random search can return nothing and withdraw a running escape. Keep the point
    // until reached, failed or timed out; weak keys retain no unloaded bodies.
    private final Map<Mob, Escape> escapes = new WeakHashMap<>();

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

    private Optional<BlockPos> away(Worker who) {
        Mob body = who.body();
        DamageSource hurt = body.getLastDamageSource();
        Mob pursuer = body.getLastHurtByMob() instanceof Mob mob && mob.getTarget() == body
            && Threats.hostile(mob, body) && body.distanceToSqr(mob) <= 16 * 16 ? mob : null;
        boolean danger = pursuer != null || hurt != null
            && (hurt.is(DamageTypeTags.PANIC_CAUSES) || hurt.is(DamageTypeTags.IS_FIRE));
        Escape escape = escapes.get(body);
        long now = body.level().getGameTime();
        boolean failed = Bodies.of(body).flatMap(resident -> resident.balk())
            .filter(balk -> escape != null && balk.at() > escape.started()
                && balk.doing().toward().orElse(balk.doing().what()).equals(FearContent.FLEEING)).isPresent();
        if (!body.isAlive() || body.isRemoved() || body.isPassenger()) {
            escapes.remove(body);
            return Optional.empty();
        }
        if (escape != null && now - escape.started() < 120 && !failed
                && !Reach.standingAt(body.position(), escape.to())) {
            return Optional.of(escape.to());
        }
        escapes.remove(body);
        if (!danger) { return Optional.empty(); }
        Entity from = pursuer != null ? pursuer : hurt == null ? null : hurt.getEntity();
        Optional<BlockPos> to = Fleeing.awayFrom(body, Optional.ofNullable(from).map(Entity::position));
        to.ifPresent(point -> escapes.put(body, new Escape(point, now)));
        return to;
    }
}
