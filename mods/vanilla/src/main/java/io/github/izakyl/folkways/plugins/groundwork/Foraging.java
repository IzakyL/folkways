package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.plugins.farming.CropBehaviors;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

// Tags choose forage; renewable plants also need a known harvest lifecycle.
final class Foraging {
    private static final TagKey<Block> HARVEST = tag("forage_harvest");
    private static final TagKey<Block> PICK = tag("forage_pick");

    private Foraging() {
    }

    static boolean renewable(BlockState state) {
        return state.is(HARVEST);
    }

    static boolean ripe(LevelReader level, BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        if (renewable(state)) {
            if (state.is(Blocks.SWEET_BERRY_BUSH)) return state.getValue(SweetBerryBushBlock.AGE) > 1;
            if (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)) return CaveVines.hasGlowBerries(state);
            return CropBehaviors.of(state.getBlock()).map(crop -> crop.ripe(level, cell)).orElse(false);
        }
        return state.is(PICK) && (!(state.getBlock() instanceof CropBlock crop) || crop.isMaxAge(state))
            && (!state.hasProperty(DoublePlantBlock.HALF)
                || state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER);
    }

    static List<BlockPos> cells(LevelReader level, BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        if (!renewable(state) && state.hasProperty(DoublePlantBlock.HALF)) {
            BlockPos upper = cell.above();
            if (!level.hasChunkAt(upper)) return List.of();
            BlockState top = level.getBlockState(upper);
            return top.is(state.getBlock()) && top.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER
                ? List.of(cell, upper) : List.of();
        }
        return List.of(cell);
    }

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("folkways", path));
    }
}
