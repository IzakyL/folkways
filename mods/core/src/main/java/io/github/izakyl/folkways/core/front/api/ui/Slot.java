package io.github.izakyl.folkways.core.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** One item in a slot frame, as a container shows it: its count in the corner and its name on hover. */
public final class Slot extends UIElement {
    public static final int SLOT = 18;
    private final ItemStack stack;

    public Slot(ItemStack stack) {
        this.stack = stack;
        layout(layout -> layout.width(SLOT).height(SLOT).flexShrink(0));
        style(style -> style.tooltips(stack.getHoverName()));
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        super.drawBackgroundAdditional(context);
        int x = Math.round(getPositionX()), y = Math.round(getPositionY());
        var graphics = context.graphics;
        graphics.fill(x, y, x + SLOT, y + SLOT, 0xFFC4AD7E);
        graphics.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0xFF8B7A5E);
        graphics.flush();
        graphics.renderItem(stack, x + 1, y + 1);
        graphics.renderItemDecorations(Minecraft.getInstance().font, stack, x + 1, y + 1);
        graphics.flush();
    }
}
