package io.github.izakyl.folkways.core.front.api.ui;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import net.minecraft.resources.ResourceLocation;

public record Bay(ResourceLocation id) {

    public static final Bay RESIDENT = new Bay(
        ResourceLocation.fromNamespaceAndPath(FolkwaysCoreMod.MOD_ID, "resident"));
}
