package io.github.izakyl.folkways.front.ui.panel;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class PanelButton extends AbstractWidget {

    private final Supplier<String> label;
    private final BooleanSupplier highlighted;
    private final Runnable action;

    private PanelButton(int x, int y, int width, int height, String token,
            Supplier<String> label, BooleanSupplier highlighted, Runnable action) {
        super(x, y, width, height, Component.literal(token));
        this.label = label;
        this.highlighted = highlighted;
        this.action = action;
    }

    public static PanelButton centred(int x, int y, int width, int height, String token,
            Supplier<String> label, BooleanSupplier highlighted, Runnable action) {
        return new PanelButton(x, y, width, height, token, label, highlighted, action);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean selected = highlighted.getAsBoolean();
        VanillaUi.button(graphics, getX(), getY(), getWidth(), getHeight(), active, isHovered() || selected);
        if (selected) {
            graphics.renderOutline(getX(), getY(), getWidth(), getHeight(), VanillaUi.SELECTED_FRAME);
        }
        Font font = Minecraft.getInstance().font;
        String text = VanillaUi.fit(font, label.get(), getWidth() - 4);
        int textX = getX() + Math.max(2, (getWidth() - font.width(text)) / 2);
        graphics.drawString(font, text, textX, getY() + (getHeight() - 8) / 2,
            VanillaUi.BUTTON_TEXT, true);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        action.run();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
