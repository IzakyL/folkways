package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.mechanics.api.Mechanics;
import java.util.List;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;

public final class VanillaRecipes {

    private VanillaRecipes() {
    }

    public static void install() {
        for (var type : List.of(RecipeType.SMELTING, RecipeType.BLASTING, RecipeType.SMOKING, RecipeType.CAMPFIRE_COOKING)) {
            Mechanics.duration(type, recipe -> ((AbstractCookingRecipe) recipe).getCookingTime());
        }
    }
}
