package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.front.api.ui.Cue;
import io.github.izakyl.folkways.front.api.ui.Cues;
import io.github.izakyl.folkways.front.api.ui.Rows;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;

/** Draws key hints, one to a line: the icons of its keys, then what they do; inked, for the ledger's paper. */
record CueLines(Cues cues, boolean inked) implements ClientTooltipComponent {

    static final int LINE = 13;

    private static final String AND = "+";
    private static final int GAP = 2;
    private static final int LABEL_GAP = 4;
    private static final int LABEL = 0xFFF0EEE6;
    private static final int JOIN = 0xFFA0A0A0;
    private static final int BAR = 0xFF6BA88A;
    private static final int BAR_BED = 0x60000000;
    private static final int INK_LABEL = Rows.INK;
    private static final int INK_JOIN = Rows.QUIET;

    // A tooltip cannot be drawn without a first line of text, and one that is hints alone has an empty one. The
    // hints are drawn over the room it takes: its own height, and the gap a tooltip leaves under its first line.
    private static final int EMPTY_HEADING = 12;

    static int width(Font font, Cue cue) {
        int width = 0;
        for (Cue.Key key : cue.keys()) {
            width += KeyGlyph.of(key).width(font);
        }
        width += Math.max(0, cue.keys().size() - 1) * (font.width(AND) + 2 * GAP);
        return width + LABEL_GAP + font.width(cue.does());
    }

    static int width(Font font, List<Cue> cues) {
        int widest = 0;
        for (Cue cue : cues) {
            widest = Math.max(widest, width(font, cue));
        }
        return widest;
    }

    CueLines(Cues cues) {
        this(cues, false);
    }

    static void draw(GuiGraphics graphics, Font font, Cue cue, int x, int y) {
        draw(graphics, font, cue, x, y, false);
    }

    static void draw(GuiGraphics graphics, Font font, Cue cue, int x, int y, boolean inked) {
        int at = x;
        for (int index = 0; index < cue.keys().size(); index++) {
            if (index > 0) {
                graphics.drawString(font, AND, at + GAP, y + 2, inked ? INK_JOIN : JOIN, false);
                at += font.width(AND) + 2 * GAP;
            }
            Cue.Key key = cue.keys().get(index);
            KeyGlyph glyph = KeyGlyph.of(key);
            glyph.draw(graphics, font, at, y, Pressed.down(key));
            at += glyph.width(font);
        }
        graphics.drawString(font, cue.does(), at + LABEL_GAP, y + 2, inked ? INK_LABEL : LABEL, !inked);
        if (cue.holding()) {
            int width = width(font, cue);
            graphics.fill(x, y + KeyGlyph.HEIGHT, x + width, y + KeyGlyph.HEIGHT + 1, BAR_BED);
            graphics.fill(x, y + KeyGlyph.HEIGHT, x + Math.round(width * Math.min(1.0F, cue.held())),
                y + KeyGlyph.HEIGHT + 1, BAR);
        }
    }

    static void draw(GuiGraphics graphics, Font font, List<Cue> cues, int x, int y) {
        draw(graphics, font, cues, x, y, false);
    }

    static void draw(GuiGraphics graphics, Font font, List<Cue> cues, int x, int y, boolean inked) {
        for (int index = 0; index < cues.size(); index++) {
            draw(graphics, font, cues.get(index), x, y + index * LINE, inked);
        }
    }

    @Override
    public int getHeight() {
        return cues.lines().size() * LINE - (cues.headed() ? 0 : EMPTY_HEADING);
    }

    @Override
    public int getWidth(Font font) {
        return width(font, cues.lines());
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
        draw(graphics, font, cues.lines(), x, cues.headed() ? y : y - EMPTY_HEADING, inked);
    }
}
