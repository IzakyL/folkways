package io.github.izakyl.folkways.core.rts.client;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * A square command-card button that wears an item as its icon; its name is in the tooltip, so a whole card fits
 * on one row. A gold frame marks the command a plain right-click on the ground issues.
 */
final class CommandButton extends Button {
    static final int SIZE = 20;
    private static final int DEFAULT = 0xFFE2B33C;
    private final ItemStack icon;
    private boolean byDefault;
    private String key = "";

    CommandButton(ItemStack icon) {
        this.icon = icon;
        noText();
        layout(layout -> layout.width(SIZE).height(SIZE).paddingAll(0).flexShrink(0));
    }

    /** The hotkey shown in the button's corner; a long name (a mouse button) shows its first two letters. */
    CommandButton key(KeyMapping mapping) {
        if (mapping != null && !mapping.isUnbound()) {
            String name = mapping.getTranslatedKeyMessage().getString();
            key = name.length() <= 2 ? name : name.substring(0, 2);
        }
        return this;
    }

    /** Marks this command as what a plain right-click does. */
    CommandButton byDefault(boolean value) {
        byDefault = value;
        return this;
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        super.drawBackgroundAdditional(context);
        int x = Math.round(getPositionX()), y = Math.round(getPositionY());
        var graphics = context.graphics;
        graphics.flush();
        graphics.renderItem(icon, x + (SIZE - 16) / 2, y + (SIZE - 16) / 2);
        if (!isActive()) {
            graphics.fill(x + 1, y + 1, x + SIZE - 1, y + SIZE - 1, 0x80EADCB6);
        }
        if (!key.isEmpty()) {
            // Small and bottom-right, as RTS command cards print their hotkeys.
            var font = Minecraft.getInstance().font;
            float scale = .75F;
            graphics.pose().pushPose();
            graphics.pose().translate(x + SIZE - 2 - font.width(key) * scale, y + SIZE - 2 - 7 * scale, 200);
            graphics.pose().scale(scale, scale, 1);
            // A dark tab behind the letter keeps it readable over any icon.
            graphics.fill(-1, -1, font.width(key), 8, 0xC0201810);
            graphics.drawString(font, key, 0, 0, 0xFFFFE29A, false);
            graphics.pose().popPose();
        }
        if (byDefault) {
            graphics.fill(x, y, x + SIZE, y + 1, DEFAULT);
            graphics.fill(x, y + SIZE - 1, x + SIZE, y + SIZE, DEFAULT);
            graphics.fill(x, y, x + 1, y + SIZE, DEFAULT);
            graphics.fill(x + SIZE - 1, y, x + SIZE, y + SIZE, DEFAULT);
        }
        graphics.flush();
    }
}
