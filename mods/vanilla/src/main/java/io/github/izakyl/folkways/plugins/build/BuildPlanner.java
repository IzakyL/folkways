package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.plugins.build.draft.Joint;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;

final class BuildPlanner {

    private BuildPlanner() {
    }

    static boolean satisfied(BlockState current, BlockState target) {
        if (flowing(target)) {
            return current.isAir() || current.getFluidState().getType().isSame(target.getFluidState().getType());
        }
        if (target.isAir()) {
            return current.isAir();
        }
        if (target.getBlock() instanceof LiquidBlock) {
            return current.equals(target);
        }
        return Fidelity.same(current, target);
    }

    static boolean flowing(BlockState target) {
        return target.getBlock() instanceof LiquidBlock && !target.getFluidState().isSource();
    }

    static BlockState wanted(BlockState target) {
        return flowing(target) ? Blocks.AIR.defaultBlockState() : target;
    }

    static boolean unlayable(Footprint print) {
        return Costs.of(print).isEmpty();
    }

    static boolean drowned(BlockState state) {
        return !(state.getBlock() instanceof LiquidBlock) && !state.hasProperty(BlockStateProperties.WATERLOGGED)
            && state.getFluidState().isSourceOfType(Fluids.WATER);
    }

    static boolean watered(Function<BlockPos, BlockState> before, Map<BlockPos, BlockState> laid) {
        for (Map.Entry<BlockPos, BlockState> cell : laid.entrySet()) {
            if (drowned(cell.getValue()) && !before.apply(cell.getKey()).getFluidState().isSourceOfType(Fluids.WATER)) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code state} would stay put at {@code pos}: it survives there, and does not fall through what is below. */
    static boolean stays(LevelReader level, BlockPos pos, BlockState state) {
        return state.canSurvive(level, pos) && !falls(level::getBlockState, pos, state);
    }

    static boolean falls(Function<BlockPos, BlockState> world, BlockPos pos, BlockState state) {
        return state.getBlock() instanceof FallingBlock && FallingBlock.isFree(world.apply(pos.below()));
    }

    static BlockState leftBehind(BlockState taken) {
        return taken.getFluidState().createLegacyBlock();
    }

    static boolean complete(ServerLevel level, BuildTarget target) {
        if (!target.unloaded().isEmpty()) {
            return false;
        }
        for (Map.Entry<BlockPos, BlockState> cell : target.cells().entrySet()) {
            BlockPos pos = cell.getKey();
            if (!level.hasChunkAt(pos) || !satisfied(level.getBlockState(pos), cell.getValue())) {
                return false;
            }
        }
        for (Joint joint : target.joints()) {
            if (!Joints.joiner().joined(level, joint.from(), joint.to())) {
                return false;
            }
        }
        return true;
    }

    static boolean holds(BlockState current, BlockState expected) {
        if (current.getBlock() instanceof LiquidBlock && expected.getBlock() instanceof LiquidBlock) {
            return current.getFluidState().getType().isSame(expected.getFluidState().getType());
        }
        return Fidelity.same(current, expected);
    }
}
