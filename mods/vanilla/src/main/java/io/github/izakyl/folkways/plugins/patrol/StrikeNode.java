package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.api.Xp;
import io.github.izakyl.folkways.plugins.combat.SpottedTargets;
import io.github.izakyl.folkways.plugins.combat.Threats;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

// BodyRunner owns the attack and its hand loan, including a weapon's continuing engagement.
final class StrikeNode implements Node {

    private final NodeSpec spec;
    private final UUID foe;
    private WeaponSession session;
    private boolean attempted;
    private boolean killed;
    private long advancedAt = Long.MIN_VALUE;
    private final BooleanSupplier authorized;

    StrikeNode(ServerLevel level, Mob foe, Stances stances) {
        this(level, foe, stances, () -> true);
    }

    StrikeNode(ServerLevel level, Mob foe, Stances stances, BooleanSupplier authorized) {
        this.authorized = authorized;
        this.foe = foe.getUUID();
        // The foe, and the cell it is struck at, as the site holds them: aboard the ship it stands on, if it does.
        WorkSite site = WorkSite.on(foe);
        Workload.Continuous attack = (world, worker) -> advance(world, worker);
        this.spec = NodeSpec.of(UUID.randomUUID(), PatrolContent.ID, site, stances, attack)
            .vocation(PatrolContent.trade())
            .doing(PatrolContent.FIGHTING)
            .focus(site.cell())
            .done();
    }

    @Override
    public boolean mountedWork() {
        return true;
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public boolean ready(ServerLevel level) {
        return authorized.getAsBoolean() && (attempted || standing(level) != null);
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        if (killed) {
            return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(PatrolContent.trade(), 2)));
        }
        return Outcome.done();
    }

    private boolean advance(ServerLevel level, Worker who) {
        LivingEntity target = standing(level);
        if (!authorized.getAsBoolean() || !(target instanceof Mob mob) || !Threats.hostile(mob, who.body())) {
            return false;
        }
        advancedAt = level.getGameTime();
        WeaponSession previous = session;
        session = Weapons.select(who, target, session);
        if (session == null) {
            return false;
        }
        if (previous != session) {
            session.leasedBy(() -> engaged(level));
        }
        if (!attempted || previous != session) {
            attempted = true;
            if (!fire(level, who, target)) {
                return false;
            }
        }
        if (session.continuousEngagement() && session.state() == WeaponSession.State.READY
            && !fire(level, who, target)) {
            return false;
        }
        session.tick();
        killed = !target.isAlive();
        return !killed
            && (session.continuousEngagement() && session.state() == WeaponSession.State.READY
            || session.state() == WeaponSession.State.FIRING
            || session.state() == WeaponSession.State.RELOADING);
    }

    private boolean fire(ServerLevel level, Worker who, LivingEntity target) {
        if (Weapons.indirect(who, target) || Weapons.longRange(who, target)) {
            Optional<SpottedTargets> spots = Bodies.of(who.body()).flatMap(body -> body.colonyId())
                .flatMap(id -> Colonies.of(level.getServer(), id)).map(SpottedTargets::of);
            Optional<SpottedTargets.Spot> spot = spots.flatMap(shared -> shared.get(who.body(), foe));
            if (spot.isEmpty() || !session.spottedTarget(target, spot.get().position(),
                () -> authorized.getAsBoolean() && spots.get().resolve(spot.get(), who.body()).isPresent())) {
                return false;
            }
        }
        return session.fire(target);
    }

    @Override
    public void released(ServerLevel level, Worker who, Ending how) {
        if (session != null) {
            session.close();
        }
    }

    // Labor runs after the entity tick, so the lease spans the one tick between two advances.
    private boolean engaged(ServerLevel level) {
        return level.getGameTime() - advancedAt <= 1 && authorized.getAsBoolean();
    }

    private LivingEntity standing(ServerLevel level) {
        Entity found = level.getEntity(foe);
        return found instanceof LivingEntity living && living.isAlive() && !living.isRemoved() ? living : null;
    }
}
