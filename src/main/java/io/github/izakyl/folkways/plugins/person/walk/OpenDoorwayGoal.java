package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
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

public final class OpenDoorwayGoal extends Goal {
    private static final int HOLD_TICKS = 20;
    private static final double INTERACT_RANGE_SQR = 2.25D;

    private final Mob mob;
    @Nullable
    private BlockPos doorway;
    private double approachX;
    private double approachZ;
    private int holdTicks;
    private boolean through;

    public OpenDoorwayGoal(Mob mob) {
        this.mob = mob;
    }

    @Override
    public boolean canUse() {
        if (!mob.horizontalCollision || !(mob.getNavigation() instanceof GroundPathNavigation navigation)) {
            return false;
        }
        Path path = navigation.getPath();
        if (path == null || path.isDone()) {
            return false;
        }
        int ahead = Math.min(path.getNextNodeIndex() + 2, path.getNodeCount());
        for (int i = 0; i < ahead; i++) {
            Node node = path.getNode(i);
            if (adopt(new BlockPos(node.x, node.y, node.z)) || adopt(new BlockPos(node.x, node.y + 1, node.z))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return doorway != null
            && openableByHand(mob.level().getBlockState(doorway))
            && (holdTicks > 0 && !through || inUse());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        holdTicks = HOLD_TICKS;
        through = false;
        approachX = world(doorway).x - mob.getX();
        approachZ = world(doorway).z - mob.getZ();
        setOpen(true);
    }

    @Override
    public void tick() {
        holdTicks = Math.max(0, holdTicks - 1);
        setOpen(true);
        double dx = world(doorway).x - mob.getX();
        double dz = world(doorway).z - mob.getZ();
        if (approachX * dx + approachZ * dz < 0.0D) {
            through = true;
        }
    }

    @Override
    public void stop() {
        if (doorway != null && !inUse()) {
            setOpen(false);
        }
    }

    // Crossing the centre has not cleared the body, and someone behind or coming the other way may still need
    // the opening. Keep holding it until bodies are clear and no nearby traveller is about to use this doorway.
    private boolean inUse() {
        BlockPos feet = doorway;
        BlockState state = mob.level().getBlockState(doorway);
        if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            feet = doorway.below();
        }
        Vec3 at = world(feet);
        AABB opening = new AABB(at.x - 0.5D, at.y, at.z - 0.5D, at.x + 0.5D, at.y + 2.0D, at.z + 0.5D);
        if (opening.intersects(mob.getBoundingBox())) {
            return true;
        }
        for (LivingEntity other : mob.level().getEntitiesOfClass(LivingEntity.class, opening.inflate(1.0D),
                entity -> entity.isAlive() && !entity.isSpectator())) {
            if (other == mob) {
                continue;
            }
            if (opening.intersects(other.getBoundingBox())
                    || other instanceof Mob traveller && traveller.distanceToSqr(at) <= INTERACT_RANGE_SQR
                        && usesDoorway(traveller)) {
                return true;
            }
        }
        return false;
    }

    private boolean usesDoorway(Mob traveller) {
        Path path = traveller.getNavigation().getPath();
        if (path == null || path.isDone()) {
            return false;
        }
        int ahead = Math.min(path.getNextNodeIndex() + 2, path.getNodeCount());
        for (int i = Math.max(0, path.getNextNodeIndex() - 1); i < ahead; i++) {
            BlockPos node = path.getNode(i).asBlockPos();
            if (node.equals(doorway) || node.above().equals(doorway)) {
                return true;
            }
        }
        return false;
    }

    private Vec3 world(BlockPos storage) {
        return WorldSpaces.world(mob.level(),
            WorldPos.of(mob.level(), storage)).orElseThrow();
    }

    private boolean adopt(BlockPos pos) {
        if (mob.distanceToSqr(world(pos).x, mob.getY(), world(pos).z) > INTERACT_RANGE_SQR) {
            return false;
        }
        BlockState state = mob.level().getBlockState(pos);
        if (!openableByHand(state) || state.getValue(BlockStateProperties.OPEN)) {
            return false;
        }
        doorway = pos.immutable();
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
