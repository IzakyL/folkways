package io.github.izakyl.folkways.front.ui.panel;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class VanillaUi {

    static final WidgetSprites BUTTON = new WidgetSprites(
        vanilla("widget/button"), vanilla("widget/button_disabled"), vanilla("widget/button_highlighted"));

    static final int BUTTON_TEXT = 0xFFFFFF;

    static final int SELECTED_FRAME = 0xFFFFFFFF;

    private VanillaUi() {
    }

    static String fit(Font font, String text, int width) {
        if (width <= 0 || font.width(text) <= width) {
            return text;
        }
        return font.plainSubstrByWidth(text, width - font.width("…")) + "…";
    }

    static void button(GuiGraphics graphics, int x, int y, int width, int height,
            boolean enabled, boolean hovered) {
        graphics.blitSprite(BUTTON.get(enabled, hovered), x, y, width, height);
    }

    private static ResourceLocation vanilla(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }
}
