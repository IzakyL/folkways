package io.github.izakyl.folkways.war;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

final class RaidIgniteGoal extends Goal {
    private final Mob mob;
    private BlockPos target;
    private int windup;
    private int cooldown;
    RaidIgniteGoal(Mob mob) { this.mob = mob; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
    @Override public boolean canUse() {
        if (mob.tickCount < cooldown || !RaidMembers.role(mob).equals("arsonist") || mob.getTarget() != null
                || !(mob.level() instanceof ServerLevel level) || !WarEdits.fireRules(level)) { return false; }
        for (BlockPos at : BlockPos.betweenClosed(mob.blockPosition().offset(-2, -1, -2), mob.blockPosition().offset(2, 2, 2))) {
            if (!level.getBlockState(at).isAir() || mob.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(at)) > 9) { continue; }
            for (Direction face : Direction.values()) {
                BlockPos fuel = at.relative(face);
                if (level.getBlockState(fuel).isFlammable(level, fuel, face.getOpposite())) { target = at.immutable(); return true; }
            }
        }
        return false;
    }
    @Override public boolean canContinueToUse() { return target != null && windup < 30 && mob.getTarget() == null; }
    @Override public void start() { windup = 0; mob.getNavigation().stop(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        mob.getLookControl().setLookAt(target.getX() + .5, target.getY() + .5, target.getZ() + .5);
        if (++windup == 30) { AttributedFires.ignite(mob, target); cooldown = mob.tickCount + 200; }
    }
    @Override public void stop() { target = null; }
}
