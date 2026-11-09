package io.github.izakyl.folkways.vanilla.combat.ui;

import io.github.izakyl.folkways.core.front.api.panel.PageKind;
import net.minecraft.resources.ResourceLocation;

public final class LoadoutContent {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "loadouts");
    public static final PageKind PAGE =
        PageKind.boarded(ID, "folkways.loadouts.title", ResourceLocation.withDefaultNamespace("armor_stand"))
            .inNavigation();

    private LoadoutContent() {
    }
}
