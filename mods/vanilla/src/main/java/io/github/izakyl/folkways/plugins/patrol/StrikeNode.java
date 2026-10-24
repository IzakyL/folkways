package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.plugins.combat.Threats;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

// One native attack, owned and ticked by BodyRunner. An interruption always closes its actual hand loan.
final class StrikeNode implements Node {

    private final NodeSpec spec;
    private final UUID foe;
    private WeaponSession session;
    private boolean attempted;
    private boolean killed;

    StrikeNode(ServerLevel level, Mob foe, Stances stances) {
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
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public boolean ready(ServerLevel level) {
        return attempted || standing(level) != null;
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
        if (!(target instanceof Mob mob) || !Threats.hostile(mob, who.body())) {
            return false;
        }
        if (!attempted) {
            attempted = true;
            session = Weapons.open(who).orElse(null);
            if (session == null || !session.fire(target)) {
                return false;
            }
        }
        session.tick();
        killed = !target.isAlive();
        return session.state() == WeaponSession.State.FIRING || session.state() == WeaponSession.State.RELOADING;
    }

    @Override
    public void released(ServerLevel level, Worker who, Ending how) {
        if (session != null) {
            session.close();
        }
    }

    private LivingEntity standing(ServerLevel level) {
        Entity found = level.getEntity(foe);
        return found instanceof LivingEntity living && living.isAlive() && !living.isRemoved() ? living : null;
    }
}
