package io.github.izakyl.folkways.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.TransformTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A sentence drawn on a page, in a line {@link Signs#HEIGHT} tall and as wide as it reads. Squeezed narrower, it
 * leaves signs off the end behind an ellipsis rather than cut one in half.
 */
public final class SignLine {

    private SignLine() {
    }

    public static UIElement of() {
        return new UIElement()
            .layout(layout -> layout.width(0).height(Signs.HEIGHT).flexShrink(1).minWidth(0));
    }

    public static void show(UIElement line, Sentence sentence) {
        show(line, sentence, Signs.WORD_COLOR);
    }

    /** Draws {@code sentence} in {@code line}, its words in {@code ink}; on the client only. */
    public static void show(UIElement line, Sentence sentence, int ink) {
        Drawn drawn = new Drawn(Signs.of(sentence, ink));
        int width = Signs.lay(Minecraft.getInstance().font, drawn.signs).width();
        line.layout(layout -> layout.width(width));
        line.style(style -> style.background(drawn));
    }

    private static final class Drawn extends TransformTexture {
        private final List<Signs.Sign> signs;
        private int room = -1;
        private Signs.Laid laid;

        private Drawn(List<Signs.Sign> signs) {
            this.signs = signs;
        }

        @Override
        protected void drawInternal(GuiGraphics graphics, float mouseX, float mouseY, float x, float y,
                float width, float height, float partialTicks) {
            Font font = Minecraft.getInstance().font;
            if (laid == null || room != (int) width) {
                room = (int) width;
                laid = Signs.lay(font, signs, room);
            }
            graphics.pose().pushPose();
            // On whole pixels: a line set half a pixel down samples the font between its rows and mangles the counts.
            graphics.pose().translate(Math.round(x), Math.round(y + (height - Signs.HEIGHT) / 2f), 0f);
            Signs.draw(graphics, font, laid, 0, 0);
            graphics.pose().popPose();
        }

        @Override
        public IGuiTexture copy() {
            return new Drawn(signs);
        }
    }
}
