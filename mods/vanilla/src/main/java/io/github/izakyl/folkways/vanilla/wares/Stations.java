package io.github.izakyl.folkways.vanilla.wares;

import io.github.izakyl.folkways.core.mechanics.api.Mechanics;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
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

    static void declare() {
        StationPrefab.register();
        for (Block block : PACK_STATIONS) {
            RecipeType<?> type = block == Blocks.CRAFTING_TABLE ? RecipeType.CRAFTING
                    : block == Blocks.STONECUTTER ? RecipeType.STONECUTTING : RecipeType.SMITHING;
            Mechanics.station(Mechanics.Station.block(block, WaresContent.STATION, WaresContent.CRAFTING,
                    List.of(type), List.of()));
        }
        COOKERS.forEach((block, type) -> Mechanics.station(Mechanics.Station.block(block,
                WaresContent.STATION, WaresContent.CRAFTING, List.of(type),
                List.of(Component.translatable("folkways.mechanics.fuel")))));
        Mechanics.recipes(new Mechanics.Source() {
            public ResourceLocation id() {
                return WaresContent.ID;
            }

            public List<Mechanics.Processing> recipes(RecipeManager recipes, HolderLookup.Provider registries) {
                List<Mechanics.Processing> result = new ArrayList<>();
                for (var holder : recipes.getRecipes()) {
                    if (FolkwaysVanillaConfig.refusedRecipes().contains(holder.id())) {
                        continue;
                    }
                    ResourceLocation type = Mechanics.recipeType(holder.value().getType());
                    for (var station : Mechanics.stations()) {
                        if (station.delegation().equals(WaresContent.STATION)
                                && station.recipeTypes().contains(type)) {
                            Mechanics.recipe(holder, station.id(), registries).ifPresent(result::add);
                        }
                    }
                }
                return List.copyOf(result);
            }
        });
    }

    static boolean isStation(BlockState state) {
        Block block = state.getBlock();
        return PACK_STATIONS.contains(block) || COOKERS.containsKey(block);
    }
}
