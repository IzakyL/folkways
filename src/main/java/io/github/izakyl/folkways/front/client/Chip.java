package io.github.izakyl.folkways.front.client;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * A button of the view from above, drawn as the key hints are: light words on a dark bed, lit along the foot when
 * on, and along half of it when half on.
 */
final class Chip extends AbstractButton {
    static final int HEIGHT = 18;

    private static final int PADDING = 4;
    private static final int ICON = 16;
    private static final int BED = 0x80000000;
    private static final int BED_HOVERED = 0xB0000000;
    private static final int LABEL = 0xFFF0EEE6;
    private static final int LABEL_OFF = 0xFFA0A0A0;
    private static final int LIT = 0xFF6BA88A;

    private final ItemStack icon;
    /** How much of it is on: none, half or all, as 0, 1 or 2. */
    private final IntSupplier lit;
    private final Runnable pressed;

    Chip(Component label, ItemStack icon, BooleanSupplier lit, Runnable pressed) {
        this(label, icon, () -> lit.getAsBoolean() ? 2 : 0, pressed);
    }

    Chip(Component label, ItemStack icon, IntSupplier lit, Runnable pressed) {
        super(0, 0, widthOf(label, icon), HEIGHT, label);
        this.icon = icon;
        this.lit = lit;
        this.pressed = pressed;
    }

    private static int widthOf(Component label, ItemStack icon) {
        return 2 * PADDING + Minecraft.getInstance().font.width(label) + (icon.isEmpty() ? 0 : ICON + 2);
    }

    @Override
    public void onPress() {
        pressed.run();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        int on = lit.getAsInt();
        graphics.fill(getX(), getY(), getX() + width, getY() + height, isHovered() ? BED_HOVERED : BED);
        int at = getX() + PADDING;
        if (!icon.isEmpty()) {
            graphics.renderItem(icon, at - 1, getY() + 1);
            at += ICON + 2;
        }
        graphics.drawString(font, getMessage(), at, getY() + (height - 8) / 2, on > 0 ? LABEL : LABEL_OFF, true);
        if (on > 0) {
            graphics.fill(getX(), getY() + height - 1, getX() + (on > 1 ? width : width / 2), getY() + height, LIT);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
