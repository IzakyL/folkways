package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.plugins.combat.Threats;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;

// Legacy Core ToolUse combat for non-person bodies, including independently implemented golems. A foe that
// has moved off or gone is no failure; the patroller is urged at it again from where it now stands.
final class ToolStrikeNode implements Node {

    private static final int SWING_TICKS = 10;

    private static final double STRIKE_REACH = Reach.PLAYER_BLOCK_REACH;

    private final NodeSpec spec;
    private final UUID foe;
    private final java.util.function.BooleanSupplier authorized;

    ToolStrikeNode(ServerLevel level, Mob foe, Stances stances) {
        this(level, foe, stances, null);
    }

    ToolStrikeNode(ServerLevel level, Mob foe, Stances stances, java.util.function.BooleanSupplier authorized) {
        this.authorized = authorized;
        this.foe = foe.getUUID();
        // The foe, and the cell it is struck at, as the site holds them: aboard the ship it stands on, if it does.
        WorkSite site = WorkSite.on(foe);
        this.spec = NodeSpec.of(UUID.randomUUID(), PatrolContent.ID, site, stances, Workload.Once.of(SWING_TICKS))
            .tools(ToolUse.of(PatrolContent.WEAPON))
            .vocation(PatrolContent.trade())
            .gesture(WorkGesture.SWING)
            .doing(PatrolContent.FIGHTING)
            .focus(site.cell())
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public boolean ready(ServerLevel level) {
        return (authorized == null || authorized.getAsBoolean()) && standing(level) != null;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        if (authorized != null && !authorized.getAsBoolean()) return Outcome.done();
        LivingEntity target = standing(level);
        Mob body = who.body();
        if (!(target instanceof Mob mob) || authorized == null && !Threats.hostile(mob, body) || !body.hasLineOfSight(target)
                || body.distanceTo(target) > STRIKE_REACH + target.getBbWidth()) {
            return Outcome.done();
        }
        body.getLookControl().setLookAt(target);
        body.swing(InteractionHand.MAIN_HAND);
        boolean hit = body.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)
            ? body.doHurtTarget(target)
            : target.hurt(level.damageSources().mobAttack(body), 1.0F);
        if (hit && !target.isAlive()) {
            return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(PatrolContent.trade(), 2)));
        }
        return Outcome.done();
    }

    private LivingEntity standing(ServerLevel level) {
        Entity found = level.getEntity(foe);
        return found instanceof LivingEntity living && living.isAlive() && !living.isRemoved() ? living : null;
    }
}
