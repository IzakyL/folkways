package io.github.izakyl.folkways.front.engine.registry;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.engine.item.ColonyBookMergeRecipe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class FolkwaysRecipes {
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
        DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, FolkwaysMod.MOD_ID);

    public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<ColonyBookMergeRecipe>>
        COLONY_BOOK_MERGE = SERIALIZERS.register(
            "colony_book_merge",
            () -> new SimpleCraftingRecipeSerializer<>(ColonyBookMergeRecipe::new));

    private FolkwaysRecipes() {
    }

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }
}
