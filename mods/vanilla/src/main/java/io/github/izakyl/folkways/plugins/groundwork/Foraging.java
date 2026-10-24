package io.github.izakyl.folkways.plugins.groundwork;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

// Forage clears tagged plants, whether or not they have fruit yet.
final class Foraging {
    private static final TagKey<Block> PLANTS = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways", "forage"));

    private Foraging() {
    }

    static boolean plant(BlockState state) {
        return state.is(PLANTS);
    }

    static boolean root(BlockState state) {
        return plant(state) && (!state.hasProperty(DoublePlantBlock.HALF)
            || state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER);
    }

    static boolean vine(BlockState state) {
        return state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT);
    }

    static List<BlockPos> cells(LevelReader level, BlockPos cell, Set<BlockPos> marked) {
        BlockState state = level.getBlockState(cell);
        if (vine(state)) {
            BlockPos bottom = cell;
            while (marked.contains(bottom.below()) && level.hasChunkAt(bottom.below())
                    && vine(level.getBlockState(bottom.below())) && plant(level.getBlockState(bottom.below()))) {
                bottom = bottom.below();
            }
            List<BlockPos> column = new ArrayList<>();
            for (BlockPos at = bottom; marked.contains(at) && level.hasChunkAt(at)
                    && vine(level.getBlockState(at)) && plant(level.getBlockState(at)); at = at.above()) {
                column.add(at.immutable());
            }
            return List.copyOf(column);
        }
        if (state.hasProperty(DoublePlantBlock.HALF)) {
            BlockPos upper = cell.above();
            if (!level.hasChunkAt(upper)) return List.of();
            BlockState top = level.getBlockState(upper);
            return top.is(state.getBlock()) && top.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER
                ? List.of(cell, upper) : List.of();
        }
        return List.of(cell);
    }
}
