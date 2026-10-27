package io.github.izakyl.folkways.raid;

import io.github.izakyl.folkways.core.engine.travel.MobTravel;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

final class RaidBreachGoal extends Goal {

    private final Mob mob;
    private BlockPos obstruction;
    private int work;
    private boolean finished;

    RaidBreachGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        var director = RaidMembers.director(mob).filter(d -> d.owns(mob) && !d.raid().paused);
        if (director.isEmpty()
            || !RaidMembers.role(mob).equals("breacher")
            || mob.getTarget() != null && mob.hasLineOfSight(mob.getTarget())) {
            return false;
        }
        obstruction = MobTravel.obstruction(mob, director.get().raid().target).orElse(null);
        return obstruction != null
            && RaidEdits.permits(
            (ServerLevel) mob.level(),
            obstruction,
            mob,
            false,
            Math.min(RaidConfig.HARDNESS.get(), director.get().raid().definition.limits().hardness()))
            && director.get().raid().broken
            < Math.min(RaidConfig.BREAKS.get(), director.get().raid().definition.limits().brokenBlocks());
    }

    @Override
    public boolean canContinueToUse() {
        return !finished
            && obstruction != null
            && !mob.level().getBlockState(obstruction).isAir()
            && mob.distanceToSqr(Vec3.atCenterOf(obstruction)) <= 9
            && RaidMembers.director(mob).filter(d -> d.owns(mob) && !d.raid().paused).isPresent()
            && (mob.getTarget() == null || !mob.hasLineOfSight(mob.getTarget()));
    }

    @Override
    public void start() {
        work = 0;
        finished = false;
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (finished || obstruction == null) {
            return;
        }
        mob.getLookControl().setLookAt(obstruction.getX() + .5, obstruction.getY() + .5, obstruction.getZ() + .5);
        int duration =
            Math.max(20, (int) (mob.level().getBlockState(obstruction).getDestroySpeed(mob.level(), obstruction) * 40));
        if (++work % 10 == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        mob.level().destroyBlockProgress(mob.getId(), obstruction, Math.min(9, work * 10 / duration));
        if (work >= duration) {
            RaidEdits.breakBlock(mob, obstruction);
            finished = true;
        }
    }

    @Override
    public void stop() {
        if (obstruction != null) {
            mob.level().destroyBlockProgress(mob.getId(), obstruction, -1);
        }
        obstruction = null;
        work = 0;
    }
}
