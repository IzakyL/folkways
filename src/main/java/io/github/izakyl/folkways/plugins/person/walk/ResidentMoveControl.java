package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.terms.ClimbableBlocks;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;

public final class ResidentMoveControl extends MoveControl {
    private static final double AT_HEIGHT = 0.1;
    private static final double CENTRED = 0.1;

    private boolean holdingSneak;

    public ResidentMoveControl(Mob mob) {
        super(mob);
    }

    @Override
    public void tick() {
        if (operation == Operation.JUMPING && ClimbableBlocks.isClimbable(mob.getInBlockState())) {
            operation = Operation.MOVE_TO;
        }
        if (tickColumn()) {
            return;
        }
        releaseSneak();
        super.tick();
    }

    private boolean tickColumn() {
        if (operation != Operation.MOVE_TO) {
            return false;
        }
        // Down a column toward a node beside it and lower: get down first. Pushing sideways on a climbable before
        // the body clears the opening bumps the block above it, and a body pushing against something while it
        // holds a climbable climbs, back up the way it came.
        if (!wantsSameColumn() && mob.getY() - wantedY > AT_HEIGHT && inAFloorlessClimbable() && !mob.onGround()) {
            operation = Operation.WAIT;
            releaseSneak();
            mob.setSpeed(0.0F);
            return true;
        }
        if (!wantsSameColumn()) {
            return false;
        }
        // Up a column: vanilla only jumps (and so climbs) when the block it stands in has collision, as a ladder's
        // plate does; a vine has none, so up a vine the body hovered half a block over its foot.
        if (wantedY - mob.getY() > AT_HEIGHT && ClimbableBlocks.isClimbable(mob.getInBlockState())) {
            operation = Operation.WAIT;
            releaseSneak();
            holdColumn();
            mob.getJumpControl().jump();
            return true;
        }
        if (mob.getY() - wantedY >= 1.0 && standingOnScaffolding()) {
            operation = Operation.WAIT;
            holdingSneak = true;
            mob.setShiftKeyDown(true);
            holdColumn();
            return true;
        }
        if (mob.getY() - wantedY > AT_HEIGHT && inAFloorlessClimbable()) {
            operation = Operation.WAIT;
            releaseSneak();
            // Down a column from its top, the body can be in the opening yet still held up by the rim beside it (the
            // edge of a floor, or a bed a little lower than a block): stopped there it never drops. Step on to the
            // middle of the column until nothing holds it.
            if (mob.onGround() && offCentre()) {
                holdColumn();
            } else {
                mob.setSpeed(0.0F);
            }
            return true;
        }
        return false;
    }

    private boolean wantsSameColumn() {
        return Mth.floor(wantedX) == mob.getBlockX() && Mth.floor(wantedZ) == mob.getBlockZ();
    }

    private boolean standingOnScaffolding() {
        return ClimbableBlocks.offersFloor(mob.level().getBlockState(mob.blockPosition().below()));
    }

    private boolean inAFloorlessClimbable() {
        return ClimbableBlocks.isClimbable(mob.getInBlockState())
            && !ClimbableBlocks.offersFloor(mob.getInBlockState());
    }

    private boolean offCentre() {
        double dx = wantedX - mob.getX();
        double dz = wantedZ - mob.getZ();
        return dx * dx + dz * dz >= CENTRED * CENTRED;
    }

    private void holdColumn() {
        double dx = wantedX - mob.getX();
        double dz = wantedZ - mob.getZ();
        if (dx * dx + dz * dz >= CENTRED * CENTRED) {
            float yaw = (float)(Mth.atan2(dz, dx) * 180.0F / (float)Math.PI) - 90.0F;
            mob.setYRot(rotlerp(mob.getYRot(), yaw, MAX_TURN));
        }
        mob.setSpeed((float)(speedModifier * mob.getAttributeValue(Attributes.MOVEMENT_SPEED)));
    }

    private void releaseSneak() {
        if (holdingSneak) {
            holdingSneak = false;
            mob.setShiftKeyDown(false);
        }
    }
}
