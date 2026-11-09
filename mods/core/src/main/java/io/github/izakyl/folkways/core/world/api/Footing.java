package io.github.izakyl.folkways.core.world.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class Footing {

    private static final double NEARLY_FULL = 14.0D / 16.0D;

    public static final TagKey<Block> NO_FOOTING = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways_core", "no_footing"));

    private Footing() {
    }

    public static boolean withFeetAt(BlockGetter blocks, BlockPos feet) {
        if (forbidsStandingAbove(blocks.getBlockState(feet.below()))) {
            return false;
        }
        if (blocks instanceof Level level && Keepouts.forbidden(level, feet)) {
            return false;
        }
        return (holdsInPlace(blocks, feet) || holdsAbove(blocks, feet.below()))
            && isPassable(blocks, feet) && isPassable(blocks, feet.above());
    }

    public static boolean holdsInPlace(BlockGetter blocks, BlockPos feet) {
        return ClimbableBlocks.isClimbable(blocks.getBlockState(feet));
    }

    public static boolean holdsAbove(BlockGetter blocks, BlockPos floor) {
        BlockState state = blocks.getBlockState(floor);
        if (forbidsStandingAbove(state)) {
            return false;
        }
        return state.isFaceSturdy(blocks, floor, Direction.UP)
            || ClimbableBlocks.offersFloor(state)
            || !state.getCollisionShape(blocks, floor).getFaceShape(Direction.UP).isEmpty()
            || nearlyFull(state.getCollisionShape(blocks, floor));
    }

    public static boolean forbidsStandingAbove(BlockState state) {
        return state.is(NO_FOOTING);
    }

    // Farmland, a dirt path, soul sand or a chest tops out a little short of the block above: a face cut at the
    // top of the block finds nothing there, yet whoever stands on it has their feet in the cell above, as on a full
    // block. Without this, a cell of farmland with farmland all round has nowhere to stand to sow it.
    private static boolean nearlyFull(VoxelShape shape) {
        return nearlyFullHeight(shape) && shape.max(Direction.Axis.Y) <= 1.0D;
    }

    public static boolean nearlyFullHeight(VoxelShape shape) {
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= NEARLY_FULL;
    }

    public static boolean isPassable(BlockGetter blocks, BlockPos pos) {
        BlockState state = blocks.getBlockState(pos);
        return ClimbableBlocks.isClimbable(state) || state.getCollisionShape(blocks, pos).isEmpty();
    }
}
