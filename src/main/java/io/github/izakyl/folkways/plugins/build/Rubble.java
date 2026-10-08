package io.github.izakyl.folkways.plugins.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;

final class Rubble {

    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private Rubble() {
    }

    static List<ItemStack> swap(ServerLevel level, LivingEntity body, Map<BlockPos, BlockState> before,
            Map<BlockPos, BlockState> after, ItemStack tool, boolean overwriting) {
        List<ItemStack> drops = new ArrayList<>();
        Map<BlockPos, BlockState> broken = new LinkedHashMap<>();
        before.forEach((cell, state) -> {
            if (overwriting ? BuildJob.inTheWay(state) : !state.isAir() && !state.liquid()) {
                drops.addAll(Block.getDrops(state, level, cell, level.getBlockEntity(cell), body, tool));
                drops.addAll(Joints.joiner().loosen(level, cell));
                broken.put(cell, state);
            }
        });
        Map<BlockPos, BlockState> laid = new LinkedHashMap<>();
        after.forEach((cell, state) -> laid.put(cell, wetted(level, cell, state)));
        for (Map.Entry<BlockPos, BlockState> cell : laid.entrySet()) {
            level.setBlock(cell.getKey(), cell.getValue(), QUIET);
        }
        broken.forEach((cell, state) -> state.spawnAfterBreak(level, cell, tool, true));
        settle(level, before, laid.keySet());
        return drops;
    }

    private static BlockState wetted(ServerLevel level, BlockPos cell, BlockState state) {
        if (!state.hasProperty(BlockStateProperties.WATERLOGGED) || !state.getValue(BlockStateProperties.WATERLOGGED)) {
            return state;
        }
        return state.setValue(BlockStateProperties.WATERLOGGED, level.getFluidState(cell).is(Fluids.WATER)
            && level.getFluidState(cell).isSource());
    }

    private static void settle(ServerLevel level, Map<BlockPos, BlockState> before, Iterable<BlockPos> cells) {
        for (BlockPos cell : cells) {
            BlockState now = level.getBlockState(cell);
            BlockState shaped = Fidelity.shaped(Block.updateFromNeighbourShapes(now, level, cell), now);
            if (shaped != now) {
                level.setBlock(cell, shaped, QUIET);
            }
        }
        for (BlockPos cell : cells) {
            BlockState old = before.getOrDefault(cell, level.getBlockState(cell));
            BlockState now = level.getBlockState(cell);
            level.blockUpdated(cell, old.getBlock());
            old.updateIndirectNeighbourShapes(level, cell, Block.UPDATE_CLIENTS);
            now.updateNeighbourShapes(level, cell, Block.UPDATE_CLIENTS);
            now.updateIndirectNeighbourShapes(level, cell, Block.UPDATE_CLIENTS);
        }
    }
}
