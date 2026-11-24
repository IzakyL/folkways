package io.github.izakyl.folkways.core.card.client;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * One option of a choice, an oak tag butted against its fellows so the options read as one control: its icon, when it
 * has one, and its label. The chosen option is pressed in and ticked in green, as a switched-on setting is; the others
 * stand raised.
 */
final class Chip extends Button {
    private static final int PAD = 5;
    private static final int INK = 0xFF2B1C12;
    private static final int CHOSEN = 0xFF3D6429;
    private static final String TICK = "✓ ";
    private static final int PALE = 0xFF8F7F62;
    static final String CHOSEN_CLASS = "folkways_chosen";
    private final ItemStack icon;
    private final Component label;
    private final boolean icons;
    private final boolean chosen;

    Chip(ItemStack icon, Component label, boolean icons, boolean chosen) {
        this.icon = icon;
        this.label = label;
        this.icons = icons;
        this.chosen = chosen;
        noText();
        addClass(CommandButton.PAPER);
        if (chosen) {
            addClass(CHOSEN_CLASS);
        }
        layout(layout -> layout.width(width()).height(height(icons)).paddingAll(0).flexShrink(0));
    }

    static float height(boolean icons) {
        return icons ? CommandButton.SIZE : 16;
    }

    float width() {
        var font = Minecraft.getInstance().font;
        return PAD + (chosen ? font.width(TICK) : 0) + (icon.isEmpty() ? 0 : 16 + 2) + font.width(label) + PAD;
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        super.drawBackgroundAdditional(context);
        var graphics = context.graphics;
        int x = Math.round(getPositionX()), y = Math.round(getPositionY());
        int h = Math.round(height(icons));
        int at = x + PAD;
        var font = Minecraft.getInstance().font;
        int color = !isActive() ? PALE : chosen ? CHOSEN : INK;
        graphics.flush();
        if (chosen) {
            graphics.drawString(font, TICK, at, y + (h - 8) / 2, color, false);
            at += font.width(TICK);
        }
        if (!icon.isEmpty()) {
            graphics.renderItem(icon, at, y + (h - 16) / 2);
            at += 18;
        }
        graphics.drawString(font, label, at, y + (h - 8) / 2, color, false);
        graphics.flush();
    }
}
