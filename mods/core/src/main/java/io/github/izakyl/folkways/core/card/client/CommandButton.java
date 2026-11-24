package io.github.izakyl.folkways.core.card.client;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * A square command-card button that wears an item as its icon; its name is in the tooltip, so a whole card fits
 * on one row. A gold frame marks the command a plain right-click on the ground issues. On paper ({@link #paper}) it
 * is an oak tag with ink words, as the desk's other buttons are.
 */
public final class CommandButton extends Button {
    public static final int SIZE = 20;
    private static final int DEFAULT = 0xFFE2B33C;
    private final ItemStack icon;
    private static final int LAMP_ON = 0xFF6FCF5A;
    private static final int LAMP_OFF = 0xFF5A4E40;
    /** The style class that dresses a button drawn here as the desk's oak tags. */
    public static final String PAPER = "folkways_press";
    private static final int INK = 0xFF2B1C12;
    private static final int PALE_INK = 0xFF8F7F62;
    private boolean byDefault;
    private String key = "";
    private Component label;
    private Boolean lamp;
    private String glyph = "";
    private boolean paper;

    public CommandButton(ItemStack icon) {
        this.icon = icon;
        noText();
        layout(layout -> layout.width(SIZE).height(SIZE).paddingAll(0).flexShrink(0));
    }

    /** The hotkey shown in the button's corner; a long name (a mouse button) shows its first two letters. */
    public CommandButton key(KeyMapping mapping) {
        if (mapping != null && !mapping.isUnbound()) {
            String name = mapping.getTranslatedKeyMessage().getString();
            key = name.length() <= 2 ? name : name.substring(0, 2);
        }
        return this;
    }

    /**
     * Words after the icon, for a card whose commands have no icons of their own (a facility's, named by its player);
     * the button grows to fit them.
     */
    public CommandButton label(Component text) {
        label = text;
        int width = width();
        layout(layout -> layout.width(width));
        return this;
    }

    // A button with neither icon nor sign is its words alone.
    private boolean bare() {
        return icon.isEmpty() && glyph.isEmpty();
    }

    /** A small lamp in the icon's corner: lit while a switch is on. */
    public CommandButton lamp(boolean on) {
        lamp = on;
        return this;
    }

    /** A sign drawn in the middle in place of an icon, such as a stepper's plus or minus. */
    public CommandButton glyph(String sign) {
        glyph = sign;
        return this;
    }

    /** Dresses the button as the desk's oak tags, its words in ink. */
    public CommandButton paper() {
        paper = true;
        addClass(PAPER);
        return this;
    }

    private int words() {
        return paper ? isActive() ? INK : PALE_INK : isActive() ? 0xFFF3E7C8 : 0xFF9C8A70;
    }

    /** The width the button takes in its row. */
    public int width() {
        if (label == null) {
            return SIZE;
        }
        int words = Minecraft.getInstance().font.width(label);
        return bare() ? words + 10 : SIZE + 2 + words + 5;
    }

    /** Marks this command as what a plain right-click does. */
    public CommandButton byDefault(boolean value) {
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
        if (!glyph.isEmpty()) {
            var font = Minecraft.getInstance().font;
            graphics.drawString(font, glyph, x + (SIZE - font.width(glyph) + 1) / 2, y + (SIZE - 8) / 2,
                words(), false);
        }
        if (!isActive() && !bare()) {
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
        if (lamp != null) {
            // Above the item, as the hotkey tab is.
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 200);
            graphics.fill(x + 1, y + 1, x + 7, y + 7, 0xE0201810);
            graphics.fill(x + 2, y + 2, x + 6, y + 6, lamp ? LAMP_ON : LAMP_OFF);
            graphics.pose().popPose();
        }
        if (label != null) {
            var font = Minecraft.getInstance().font;
            graphics.drawString(font, label, x + (bare() ? 5 : SIZE + 2), y + (SIZE - 8) / 2, words(), false);
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
