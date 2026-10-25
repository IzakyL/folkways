package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.terms.ClimbableBlocks;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

public final class ResidentMoveControl extends MoveControl {
    private static final double AT_HEIGHT = 0.1;
    private static final double CENTRED = 0.1;
    private static final double RIGHT = 0.19D;

    final ResidentTraffic traffic;
    private boolean holdingSneak;
    private double rightX;
    private double rightZ;
    private long centredUntil;

    public ResidentMoveControl(Mob mob) {
        super(mob);
        traffic = new ResidentTraffic(mob);
    }

    public boolean yielding() { return traffic.yielding(); }

    // Conveyances can steer standing passengers without making them ride a Minecraft vehicle.
    public void centreForTransport() { centredUntil = mob.level().getGameTime() + 20; }

    @Override
    public void tick() {
        traffic.tick();
        traffic.clear();
        if (operation == Operation.JUMPING && ClimbableBlocks.isClimbable(mob.getInBlockState())) {
            operation = Operation.MOVE_TO;
        }
        if (tickColumn()) {
            rightX = rightZ = 0;
            return;
        }
        releaseSneak();
        if (!tickGate()) {
            tickWalking();
        } else {
            rightX = rightZ = 0;
        }
    }

    private void tickWalking() {
        Path path = mob.getNavigation().getPath();
        if (!ordinaryWalking(path)) {
            rightX = rightZ = 0;
            super.tick();
            return;
        }
        Vec3 direction = ResidentTraffic.direction(path);
        double offset = RIGHT;
        int next = path.getNextNodeIndex();
        if (next + 1 == path.getNodeCount()) {
            offset = 0;
        } else if (next > 0) {
            var node = path.getNodePos(next);
            var after = path.getNodePos(next + 1);
            Vec3 outgoing = new Vec3(after.getX() - node.getX(), 0, after.getZ() - node.getZ()).normalize();
            // Centre before turning, then ease into the next lane rather than swapping sides at a corner.
            offset *= Math.max(0, direction.dot(outgoing));
        }
        rightX += Mth.clamp(-direction.z * offset - rightX, -0.04D, 0.04D);
        rightZ += Mth.clamp(direction.x * offset - rightZ, -0.04D, 0.04D);
        double x = wantedX;
        double z = wantedZ;
        double speed = speedModifier;
        try {
            // Each target stays inside its path cell with room for the full block-collision body.
            double margin = Math.max(0, (1.0D - mob.getBbWidth()) / 2.0D - 0.01D);
            wantedX += Mth.clamp(rightX, -margin, margin);
            wantedZ += Mth.clamp(rightZ, -margin, margin);
            speedModifier *= traffic.speed(direction);
            super.tick();
        } finally {
            wantedX = x;
            wantedZ = z;
            speedModifier = speed;
        }
    }

    private boolean ordinaryWalking(Path path) {
        if (operation != Operation.MOVE_TO || path == null || path.isDone() || !mob.onGround()
                || mob.isPassenger() || mob.isInWater() || Math.abs(wantedY - mob.getY()) > AT_HEIGHT
                || centredUntil >= mob.level().getGameTime() || WorldSpaces.at(mob).realm() instanceof Realm.Frame) {
            return false;
        }
        int next = path.getNextNodeIndex();
        int height = path.getNodePos(next).getY();
        if (specialCell(mob.blockPosition())) {
            return false;
        }
        for (int i = Math.max(0, next - 1); i <= Math.min(path.getNodeCount() - 1, next + 1); i++) {
            var node = path.getNodePos(i);
            if (node.getY() != height || specialCell(new BlockPos(node.getX(), node.getY(), node.getZ()))) {
                return false;
            }
        }
        return true;
    }

    private boolean specialCell(BlockPos at) {
        var feet = mob.level().getBlockState(at);
        var floor = mob.level().getBlockState(at.below());
        return ClimbableBlocks.isClimbable(feet) || ClimbableBlocks.isClimbable(floor)
            || feet.is(BlockTags.DOORS) || feet.getBlock() instanceof FenceGateBlock
            || feet.getBlock() instanceof StairBlock || floor.getBlock() instanceof StairBlock;
    }

    // Vanilla jumps out of collision at the feet, except for doors and fences. A shut fence gate is neither:
    // the body reaches its cell before the doorway goal opens it, and asks for a jump first (issue #20). Keep
    // steering through a gate without that collision jump; a higher destination still needs its usual jump.
    private boolean tickGate() {
        if (operation != Operation.MOVE_TO || !(mob.getInBlockState().getBlock() instanceof FenceGateBlock)) {
            return false;
        }
        operation = Operation.WAIT;
        double dx = wantedX - mob.getX();
        double dy = wantedY - mob.getY();
        double dz = wantedZ - mob.getZ();
        if (dx * dx + dy * dy + dz * dz < MIN_SPEED_SQR) {
            mob.setZza(0.0F);
            return true;
        }
        float yaw = (float)(Mth.atan2(dz, dx) * 180.0F / (float)Math.PI) - 90.0F;
        mob.setYRot(rotlerp(mob.getYRot(), yaw, MAX_TURN));
        mob.setSpeed((float)(speedModifier * mob.getAttributeValue(Attributes.MOVEMENT_SPEED)));
        if (dy > mob.maxUpStep() && dx * dx + dz * dz < Math.max(1.0F, mob.getBbWidth())) {
            mob.getJumpControl().jump();
            operation = Operation.JUMPING;
        }
        return true;
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
