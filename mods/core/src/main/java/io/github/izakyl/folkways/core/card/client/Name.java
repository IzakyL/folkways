package io.github.izakyl.folkways.core.card.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/** A setting's name before its control, with the hotkey that steps it in a small tab when it has one. */
final class Name extends UIElement {
    private static final int NAME_ROOM = 120;
    private final ItemStack icon;
    private final List<FormattedCharSequence> lines;
    private final String key;
    private final float width;

    Name(ItemStack icon, Component text, @Nullable KeyMapping mapping) {
        this.icon = icon;
        lines = Minecraft.getInstance().font.split(text, NAME_ROOM);
        String name = mapping == null || mapping.isUnbound() ? "" : mapping.getTranslatedKeyMessage().getString();
        this.key = name.length() <= 2 ? name : name.substring(0, 2);
        width = width(icon, text, key);
        layout(layout -> layout.width(width)
            .height(12 + Math.max(0, lines.size() - 1) * 10).flexShrink(0));
        style(style -> style.tooltips(text));
    }

    /** How wide the name is drawn. */
    float width() {
        return width;
    }

    static float width(ItemStack icon, Component text, String key) {
        var font = Minecraft.getInstance().font;
        return Math.min(NAME_ROOM, font.width(text)) + (icon.isEmpty() ? 0 : 14)
            + (key.isEmpty() ? 0 : Math.round(font.width(key) * .75F) + 6);
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        super.drawBackgroundAdditional(context);
        var graphics = context.graphics;
        var font = Minecraft.getInstance().font;
        int x = Math.round(getPositionX()), y = Math.round(getPositionY());
        if (!icon.isEmpty()) {
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 0);
            graphics.pose().scale(.75F, .75F, 1);
            graphics.renderItem(icon, 0, 0);
            graphics.pose().popPose();
            x += 14;
        }
        int textWidth = 0;
        for (int line = 0; line < lines.size(); line++) {
            FormattedCharSequence words = lines.get(line);
            graphics.drawString(font, words, x, y + 2 + line * 10, Rows.INK, false);
            textWidth = Math.max(textWidth, font.width(words));
        }
        if (!key.isEmpty()) {
            float scale = .75F;
            graphics.pose().pushPose();
            graphics.pose().translate(x + textWidth + 4, y + 3, 200);
            graphics.pose().scale(scale, scale, 1);
            graphics.fill(-1, -1, font.width(key), 8, 0xC0201810);
            graphics.drawString(font, key, 0, 0, 0xFFFFE29A, false);
            graphics.pose().popPose();
        }
    }
}
