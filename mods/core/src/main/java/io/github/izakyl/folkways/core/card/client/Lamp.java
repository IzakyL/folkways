package io.github.izakyl.folkways.core.card.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;

/** A small square lamp in a status colour. */
final class Lamp extends UIElement {
    private final int color;

    Lamp(int color) {
        this.color = color;
        layout(layout -> layout.width(7).height(7).flexShrink(0));
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        super.drawBackgroundAdditional(context);
        int x = Math.round(getPositionX()), y = Math.round(getPositionY());
        context.graphics.fill(x, y, x + 7, y + 7, 0xC0201810);
        context.graphics.fill(x + 1, y + 1, x + 6, y + 6, color);
    }
}
