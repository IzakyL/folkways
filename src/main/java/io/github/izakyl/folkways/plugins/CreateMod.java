package io.github.izakyl.folkways.plugins;

import net.neoforged.fml.ModList;

public final class CreateMod {

    public static final String ID = "create";

    private CreateMod() {
    }

    public static boolean loaded() {
        return ModList.get().isLoaded(ID);
    }
}
