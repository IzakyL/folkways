package io.github.izakyl.folkways.front.api.ui;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.resources.ResourceLocation;

public record Bay(ResourceLocation id) {

    public static final Bay RESIDENT = new Bay(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "resident"));
}
