package io.github.izakyl.folkways.core.api.terms;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

public final class Footing {

    private Footing() {
    }

    public static boolean withFeetAt(BlockGetter blocks, BlockPos feet) {
        return (holdsInPlace(blocks, feet) || holdsAbove(blocks, feet.below()))
            && isPassable(blocks, feet) && isPassable(blocks, feet.above());
    }

    public static boolean holdsInPlace(BlockGetter blocks, BlockPos feet) {
        return ClimbableBlocks.isClimbable(blocks.getBlockState(feet));
    }

    public static boolean holdsAbove(BlockGetter blocks, BlockPos floor) {
        BlockState state = blocks.getBlockState(floor);
        return state.isFaceSturdy(blocks, floor, Direction.UP)
            || ClimbableBlocks.offersFloor(state)
            || !state.getCollisionShape(blocks, floor).getFaceShape(Direction.UP).isEmpty();
    }

    public static boolean isPassable(BlockGetter blocks, BlockPos pos) {
        BlockState state = blocks.getBlockState(pos);
        return ClimbableBlocks.isClimbable(state) || state.getCollisionShape(blocks, pos).isEmpty();
    }
}
