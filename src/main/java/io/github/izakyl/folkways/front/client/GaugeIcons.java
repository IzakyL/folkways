package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.resources.ResourceLocation;

/** How a vital sits on a card: its meter's icon beside a bar in the meter's colour. */
final class GaugeIcons {
    static final int ICON = 9;
    static final int BAR_WIDTH = 24;
    static final int BAR_HEIGHT = 3;
    static final int GAP = 2;
    static final int WIDTH = ICON + GAP + BAR_WIDTH;
    static final int TRACK_COLOR = 0xC0202020;

    /** A plain white sprite, tinted to draw bars in the same pass as the icons. */
    static final ResourceLocation FILL = ours("hud/fill");

    private GaugeIcons() {
    }

    private static ResourceLocation ours(String path) {
        return ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, path);
    }
}
