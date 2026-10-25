package io.github.izakyl.folkways.plugins.combat.command;

import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** An explicit target's approach and real native hand session, both owned solely by BodyRunner. */
final class AttackNode implements Node {
    private final ResidentCommands commands;
    private final ResidentCommands.Order order;
    private final UUID step;
    private final NodeSpec spec;
    private final boolean reachable;
    private WeaponSession session;
    private RefusalKind failure;

    AttackNode(ResidentCommands commands, ResidentCommands.Order order, Worker who) {
        this.commands = commands;
        this.order = order;
        step = order.step();
        Mob target = commands.attackTarget(order).orElseThrow();
        ServerLevel level = (ServerLevel) who.body().level();
        Optional<Stances> stances = approach(level, who, target);
        reachable = stances.isPresent();
        Workload.Continuous attack = this::advance;
        spec = NodeSpec.of(step, CommandContent.ID, WorkSite.on(target), stances.orElse(Stances.WHEREVER), attack)
            .done();
    }

    public NodeSpec spec() { return spec; }
    public boolean directTravel() { return true; }
    public boolean ready(ServerLevel level) { return step.equals(order.step()) && commands.active(order); }
    public Outcome commit(ServerLevel level, Worker who) { return failure == null ? Outcome.done() : Outcome.failed(failure); }

    private boolean advance(ServerLevel level, Worker who) {
        if (!reachable) { failure = LaborRefusal.NO_WAY_THERE; return false; }
        Mob target = commands.attackTarget(order).orElse(null);
        if (target == null || !commands.permitsAttack(order, step, who, target)) { return false; }
        if (session == null) {
            session = Weapons.open(who).orElse(null);
            if (session == null || !session.explicitTarget(target,
                    () -> commands.permitsAttack(order, step, who, target))) {
                failure = LaborRefusal.GOODS_GONE;
                return false;
            }
        }
        if (session.state() == WeaponSession.State.READY && !session.fire(target)) { return false; }
        session.tick();
        io.github.izakyl.folkways.plugins.combat.CombatReadiness.fighting(who.body());
        return target.isAlive() && session.state() != WeaponSession.State.CLOSED
            && session.state() != WeaponSession.State.STOPPING;
    }

    public void released(ServerLevel level, Worker who, Ending how) {
        if (session != null) { session.close(); }
        commands.ended(order, how);
    }

    private static Optional<Stances> approach(ServerLevel level, Worker who, Mob target) {
        Mob body = who.body();
        double range = Weapons.range(who);
        if (body.distanceTo(target) <= range && body.hasLineOfSight(target)) { return Optional.of(Stances.WHEREVER); }
        BlockPos at = WorldSpaces.storage(level, WorldSpaces.at(target)).orElse(target.blockPosition());
        Set<BlockPos> cells = new LinkedHashSet<>();
        double safeRange = Math.max(1, range - Math.hypot(.9, 1));
        if (Weapons.ranged(who)) {
            Vec3 toward = body.position().subtract(target.position()).multiply(1, 0, 1).normalize();
            for (int distance = 2; distance <= safeRange; distance++) {
                for (double angle : new double[] {-.5, 0, .5}) {
                    Vec3 point = Vec3.atBottomCenterOf(at).add(toward.yRot((float) angle).scale(distance));
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos feet = BlockPos.containing(point).offset(0, dy, 0);
                        if (level.hasChunkAt(feet) && Footing.withFeetAt(level, feet)
                                && Reach.inSight(level, feet, at)) { cells.add(feet); }
                    }
                }
            }
        } else {
            for (BlockPos feet : Reach.footingsAround(level, at, 3, 2)) {
                if (WorldSpaces.world(level, WorldSpaces.at(level, feet))
                        .filter(point -> point.distanceToSqr(target.position()) <= safeRange * safeRange).isPresent()
                        && Reach.inSight(level, feet, at)) { cells.add(feet); }
            }
        }
        return Stances.of(cells.stream().map(cell -> WorldSpaces.at(level, cell)).toList());
    }
}
