package io.github.izakyl.folkways.plugins.wares;

import java.util.List;
import java.util.Map;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class Stations {

    static final int LABOR_TICKS = 20;

    static final List<Block> PACK_STATIONS =
        List.of(Blocks.CRAFTING_TABLE, Blocks.STONECUTTER, Blocks.SMITHING_TABLE);

    static final Map<Block, RecipeType<? extends AbstractCookingRecipe>> COOKERS = Map.of(
        Blocks.FURNACE, RecipeType.SMELTING,
        Blocks.BLAST_FURNACE, RecipeType.BLASTING,
        Blocks.SMOKER, RecipeType.SMOKING);

    private Stations() {
    }

    static boolean isStation(BlockState state) {
        Block block = state.getBlock();
        return PACK_STATIONS.contains(block) || COOKERS.containsKey(block);
    }
}
