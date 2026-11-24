package io.github.izakyl.folkways.core.front.client;

import io.github.izakyl.folkways.core.front.api.ui.Cue;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The key that presses a button, printed small in the button's corner as the command cards print theirs: gold on a
 * dark tab. Keys that have a button wear it there rather than take a line among the key hints.
 */
public final class KeyBadge {
    private static final float SMALL = .75F;
    private static final int INSET = 2;
    private static final int TAB = 0xC0201810;
    private static final int INK = 0xFFFFE29A;
    private static final int LIT = 0xFFE0662B;
    /**
     * A mouse drawn in the badge, in its unscaled pixels, as tall as the tab: two buttons over a body, the gaps between
     * them left dark. One more pixel is left after it, as after a letter.
     */
    private static final int MOUSE_WIDTH = 7;

    private KeyBadge() {
    }

    // Three quarters size, unless a screen pixel is a GUI pixel, where letters that small smear.
    private static float scale() {
        return Minecraft.getInstance().getWindow().getGuiScale() <= 1.0D ? 1.0F : SMALL;
    }

    /** How wide the badge for these keys is drawn, in screen pixels, its inset to the corner included. */
    public static int width(Cue.Key... keys) {
        return (int) Math.ceil((inner(glyphs(keys)) + 1) * scale()) + INSET;
    }

    /** Draws the badge for these keys with its bottom right in the corner {@code right}, {@code bottom}. */
    public static void corner(GuiGraphics graphics, int right, int bottom, Cue.Key... keys) {
        List<KeyGlyph> glyphs = glyphs(keys);
        draw(graphics, glyphs, right - INSET - inner(glyphs) * scale(), bottom - INSET - 7 * scale());
    }

    /** Draws a badge of plain words, such as a group's number, with its top left at {@code left}, {@code top}. */
    public static void at(GuiGraphics graphics, String words, int left, int top) {
        // The tab reaches one of its pixels above and left of the words.
        draw(graphics, List.of(new KeyGlyph(KeyGlyph.Kind.CAP, words)), left + scale(), top + scale());
    }

    private static List<KeyGlyph> glyphs(Cue.Key... keys) {
        List<KeyGlyph> glyphs = new ArrayList<>();
        for (Cue.Key key : keys) {
            KeyGlyph glyph = KeyGlyph.of(key);
            // A long name is shortened to fit a corner.
            glyphs.add(glyph.name().equals("Backspace") ? new KeyGlyph(KeyGlyph.Kind.CAP, "Bksp") : glyph);
        }
        return glyphs;
    }

    // In the badge's own pixels, the trailing pixel of the last part included.
    private static int inner(List<KeyGlyph> glyphs) {
        Font font = Minecraft.getInstance().font;
        int width = 0;
        for (KeyGlyph glyph : glyphs) {
            width += glyph.kind() == KeyGlyph.Kind.CAP ? font.width(glyph.name()) : MOUSE_WIDTH + 1;
        }
        return width;
    }

    private static void draw(GuiGraphics graphics, List<KeyGlyph> glyphs, float x, float y) {
        Font font = Minecraft.getInstance().font;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 200.0F);
        graphics.pose().scale(scale(), scale(), 1.0F);
        graphics.fill(-1, -1, inner(glyphs), 8, TAB);
        int at = 0;
        for (KeyGlyph glyph : glyphs) {
            if (glyph.kind() == KeyGlyph.Kind.CAP) {
                graphics.drawString(font, glyph.name(), at, 0, INK, false);
                at += font.width(glyph.name());
                continue;
            }
            // A mouse as the key hints draw it: the part to use lit, the rest pale.
            graphics.fill(at, -1, at + 3, 3, glyph.kind() == KeyGlyph.Kind.LEFT ? LIT : INK);
            graphics.fill(at + 4, -1, at + MOUSE_WIDTH, 3, glyph.kind() == KeyGlyph.Kind.RIGHT ? LIT : INK);
            graphics.fill(at, 4, at + MOUSE_WIDTH, 8, INK);
            if (glyph.kind() == KeyGlyph.Kind.WHEEL) {
                // The wheel runs on past the buttons, so it is not taken for either.
                graphics.fill(at + 3, -1, at + 4, 5, LIT);
            }
            at += MOUSE_WIDTH + 1;
        }
        graphics.pose().popPose();
    }
}
