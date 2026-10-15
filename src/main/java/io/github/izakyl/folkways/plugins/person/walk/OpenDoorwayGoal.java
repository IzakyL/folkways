package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// A resident opens a shut doorway it bumps into on its path, or joins one other residents are using ahead of
// it: it counts itself in on start and out on stop, once its own body is through. The last one out closes it.
public final class OpenDoorwayGoal extends Goal {
    private static final int HOLD_TICKS = 20;
    private static final double INTERACT_RANGE_SQR = 2.25D;

    private final Mob mob;
    @Nullable
    private BlockPos doorway;
    private double approachX;
    private double approachZ;
    private int holdTicks;
    private boolean entered;

    public OpenDoorwayGoal(Mob mob) {
        this.mob = mob;
    }

    @Override
    public boolean canUse() {
        if (!(mob.getNavigation() instanceof GroundPathNavigation navigation)) {
            return false;
        }
        Path path = navigation.getPath();
        if (path == null || path.isDone()) {
            return false;
        }
        int ahead = Math.min(path.getNextNodeIndex() + 2, path.getNodeCount());
        for (int i = 0; i < ahead; i++) {
            Node node = path.getNode(i);
            boolean upcoming = i >= path.getNextNodeIndex() - 1;
            if (adopt(new BlockPos(node.x, node.y, node.z), upcoming)
                    || adopt(new BlockPos(node.x, node.y + 1, node.z), upcoming)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return doorway != null
            && openableByHand(mob.level().getBlockState(doorway))
            && (inOpening() || holdTicks > 0 && !through());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        holdTicks = HOLD_TICKS;
        entered = false;
        approachX = world(doorway).x - mob.getX();
        approachZ = world(doorway).z - mob.getZ();
        Doorways.enter(mob.level(), doorway, mob);
        setOpen(true);
    }

    @Override
    public void tick() {
        holdTicks--;
        entered |= inOpening();
    }

    @Override
    public void stop() {
        if (doorway != null && Doorways.leave(mob.level(), doorway, mob)) {
            setOpen(false);
        }
        doorway = null;
    }

    private boolean through() {
        double dx = world(doorway).x - mob.getX();
        double dz = world(doorway).z - mob.getZ();
        return entered || approachX * dx + approachZ * dz < 0.0D;
    }

    // Crossing the doorway's centre has not cleared the body: hold it until the whole body is out.
    private boolean inOpening() {
        Vec3 at = world(doorway);
        return new AABB(at.x - 0.5D, at.y, at.z - 0.5D, at.x + 0.5D, at.y + 2.0D, at.z + 0.5D)
            .intersects(mob.getBoundingBox());
    }

    private Vec3 world(BlockPos storage) {
        return WorldSpaces.world(mob.level(),
            WorldPos.of(mob.level(), storage)).orElseThrow();
    }

    // A shut doorway is opened when the body bumps into it; an open one is only joined when other residents
    // are using it and it lies ahead, so a doorway a player left open is walked through and left alone.
    private boolean adopt(BlockPos pos, boolean upcoming) {
        if (mob.distanceToSqr(world(pos).x, mob.getY(), world(pos).z) > INTERACT_RANGE_SQR) {
            return false;
        }
        BlockState state = mob.level().getBlockState(pos);
        if (!openableByHand(state)) {
            return false;
        }
        BlockPos door = state.getBlock() instanceof DoorBlock
            && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        if (state.getValue(BlockStateProperties.OPEN)
                ? !upcoming || !Doorways.inUse(mob.level(), door)
                : !mob.horizontalCollision) {
            return false;
        }
        doorway = door.immutable();
        return true;
    }

    private void setOpen(boolean open) {
        if (doorway == null) {
            return;
        }
        Level level = mob.level();
        BlockState state = level.getBlockState(doorway);
        if (state.getBlock() instanceof DoorBlock door) {
            door.setOpen(mob, level, state, doorway, open);
        } else if (state.getBlock() instanceof FenceGateBlock gate && state.getValue(FenceGateBlock.OPEN) != open) {
            level.setBlock(doorway, state.setValue(FenceGateBlock.OPEN, open),
                Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
            level.playSound(null, doorway, open ? gate.openSound : gate.closeSound, SoundSource.BLOCKS,
                1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
            level.gameEvent(mob, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, doorway);
        }
    }

    private static boolean openableByHand(BlockState state) {
        return state.getBlock() instanceof FenceGateBlock
            || state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand();
    }
}
