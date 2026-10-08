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
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
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
            && holdTicks > 0
            && !through
            && openableByHand(mob.level().getBlockState(doorway));
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
        holdTicks--;
        double dx = world(doorway).x - mob.getX();
        double dz = world(doorway).z - mob.getZ();
        if (approachX * dx + approachZ * dz < 0.0D) {
            through = true;
        }
    }

    @Override
    public void stop() {
        setOpen(false);
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
