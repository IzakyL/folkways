package io.github.izakyl.folkways.core.front.client;

import io.github.izakyl.folkways.core.front.api.ui.Cue;
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

    private static final int BARE_PADDING = 2;
    private static final int DIMMED = 0xA0000000;

    private final ItemStack icon;
    /** How much of it is on: none, half or all, as 0, 1 or 2. */
    private final IntSupplier lit;
    private final Runnable pressed;
    /** Only its icon shows; its name is told when hovered, and stays its label for whoever reads the screen. */
    private final boolean bare;
    /** The keys that press it too, worn small in its corner; none for most. */
    private Cue.Key[] keys = new Cue.Key[0];

    Chip(Component label, ItemStack icon, BooleanSupplier lit, Runnable pressed) {
        this(label, icon, () -> lit.getAsBoolean() ? 2 : 0, pressed, false);
    }

    Chip(Component label, ItemStack icon, IntSupplier lit, Runnable pressed) {
        this(label, icon, lit, pressed, false);
    }

    private Chip(Component label, ItemStack icon, IntSupplier lit, Runnable pressed, boolean bare) {
        super(0, 0, bare ? 2 * BARE_PADDING + ICON : widthOf(label, icon), HEIGHT, label);
        this.icon = icon;
        this.lit = lit;
        this.pressed = pressed;
        this.bare = bare;
    }

    /** A toolbar button: its icon alone, or with its name beside it while it is the one picked. */
    static Chip tool(Component label, ItemStack icon, BooleanSupplier lit, Runnable pressed, boolean named) {
        return new Chip(label, icon, () -> lit.getAsBoolean() ? 2 : 0, pressed, !named);
    }

    /**
     * Wears the keys that press it in its corner: over the icon's corner when only the icon shows, otherwise after
     * the words, the button grown to make room.
     */
    Chip keys(Cue.Key... pressedBy) {
        if (!bare && keys.length == 0) {
            setWidth(getWidth() + KeyBadge.width(pressedBy));
        }
        keys = pressedBy;
        return this;
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
        graphics.fill(getX(), getY(), getX() + width, getY() + height, isHovered() && active ? BED_HOVERED : BED);
        if (bare) {
            graphics.renderItem(icon, getX() + BARE_PADDING, getY() + 1);
            if (!active) {
                graphics.pose().pushPose();
                graphics.pose().translate(0.0F, 0.0F, 200.0F);
                graphics.fill(getX() + BARE_PADDING, getY() + 1, getX() + BARE_PADDING + ICON, getY() + 1 + ICON,
                    DIMMED);
                graphics.pose().popPose();
            }
        } else {
            int at = getX() + PADDING;
            if (!icon.isEmpty()) {
                graphics.renderItem(icon, at - 1, getY() + 1);
                at += ICON + 2;
            }
            graphics.drawString(font, getMessage(), at, getY() + (height - 8) / 2,
                on > 0 && active ? LABEL : LABEL_OFF, true);
        }
        if (keys.length > 0) {
            KeyBadge.corner(graphics, getX() + width, getY() + height, keys);
        }
        if (on > 0) {
            graphics.fill(getX(), getY() + height - 1, getX() + (on > 1 ? width : width / 2), getY() + height, LIT);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
