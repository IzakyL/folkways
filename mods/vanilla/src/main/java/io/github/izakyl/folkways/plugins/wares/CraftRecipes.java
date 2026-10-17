package io.github.izakyl.folkways.plugins.wares;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeManager;

final class CraftRecipes {

    private final MinecraftServer server;

    private volatile RecipeIndex recipes;

    CraftRecipes(MinecraftServer server) {
        this.server = server;
    }

    RecipeIndex recipes() {
        RecipeManager manager = server.overworld().getRecipeManager();
        RecipeIndex held = recipes;
        if (held == null || !held.reads(manager)) {
            held = RecipeIndex.over(server.overworld(), manager);
            recipes = held;
        }
        return held;
    }

    public void closed() {
        recipes = null;
    }
}
