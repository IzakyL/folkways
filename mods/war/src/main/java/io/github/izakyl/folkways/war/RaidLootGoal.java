package io.github.izakyl.folkways.war;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.travel.MobTravel;
import java.util.EnumSet;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

final class RaidLootGoal extends Goal {
    private final Mob mob;
    private int work;
    RaidLootGoal(Mob mob) { this.mob = mob; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
    @Override public boolean canUse() {
        return RaidMembers.director(mob).filter(d -> d.owns(mob) && d.raid().phase == RaidInstance.Phase.PLUNDER && !d.raid().paused).isPresent()
            && (mob.getTarget() == null || mob.distanceToSqr(mob.getTarget()) > 36 || !mob.hasLineOfSight(mob.getTarget()));
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        var director = RaidMembers.director(mob).orElseThrow();
        WorldPos store = RaidPlunder.full(director, mob) ? null : RaidPlunder.target(director, mob);
        WorldPos destination = store == null ? director.raid().origin : store;
        if (mob.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(destination.block(mob.level()))) <= 9) {
            mob.getNavigation().stop();
            if (store == null) { director.escaped(mob); }
            else if (++work >= 40) { RaidPlunder.take(director, mob, store); work = 0; }
        } else {
            work = 0;
            if (mob.tickCount % 20 == 0) { MobTravel.approach(mob, destination, 1); }
        }
    }
    @Override public void stop() { work = 0; mob.getNavigation().stop(); }
}
