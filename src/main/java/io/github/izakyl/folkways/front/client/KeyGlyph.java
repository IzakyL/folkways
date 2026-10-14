package io.github.izakyl.folkways.front.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.izakyl.folkways.front.api.ui.Cue;
import io.github.izakyl.folkways.front.client.ponder.PonderHint;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** The icon of one input: a keycap with the key's name on it, or a mouse with the part to use lit. */
record KeyGlyph(Kind kind, String name) {

    enum Kind {
        CAP,
        LEFT,
        RIGHT,
        WHEEL
    }

    static final int HEIGHT = 11;

    private static final int MOUSE_WIDTH = 9;
    private static final int CAP_PADDING = 3;

    private static final int FACE = 0xFFE6E6E6;
    private static final int EDGE = 0xFF8C8C8C;
    private static final int INK = 0xFF202020;
    private static final int LIT = 0xFFE0662B;

    static KeyGlyph of(Cue.Key key) {
        Minecraft minecraft = Minecraft.getInstance();
        return switch (key) {
            case CLICK -> new KeyGlyph(Kind.LEFT, "");
            case ALT_CLICK -> new KeyGlyph(Kind.RIGHT, "");
            case WHEEL -> new KeyGlyph(Kind.WHEEL, "");
            case USE -> bound(minecraft.options.keyUse);
            case SNEAK -> bound(minecraft.options.keyShift);
            case JUMP -> bound(minecraft.options.keyJump);
            case MODIFIER -> cap(Minecraft.ON_OSX ? "Cmd" : "Ctrl");
            case ALTERNATE -> cap(Minecraft.ON_OSX ? "Opt" : "Alt");
            case OVERHEAD -> bound(Overhead.KEY);
            case WALK -> cap(bound(minecraft.options.keyUp).name + bound(minecraft.options.keyLeft).name
                + bound(minecraft.options.keyDown).name + bound(minecraft.options.keyRight).name);
            case DRAG -> new KeyGlyph(Kind.WHEEL, "");
            case ESCAPE -> cap("Esc");
            case TAB -> cap("Tab");
            case BACKSPACE -> cap("Backspace");
            case DIGITS -> cap("0-9");
            case PONDER -> bound(PonderHint.mapping());
        };
    }

    private static KeyGlyph cap(String name) {
        return new KeyGlyph(Kind.CAP, name);
    }

    private static KeyGlyph bound(KeyMapping mapping) {
        if (mapping == null) {
            return cap("?");
        }
        InputConstants.Key key = mapping.getKey();
        if (key.getType() == InputConstants.Type.MOUSE) {
            switch (key.getValue()) {
                case InputConstants.MOUSE_BUTTON_LEFT:
                    return new KeyGlyph(Kind.LEFT, "");
                case InputConstants.MOUSE_BUTTON_RIGHT:
                    return new KeyGlyph(Kind.RIGHT, "");
                case InputConstants.MOUSE_BUTTON_MIDDLE:
                    return new KeyGlyph(Kind.WHEEL, "");
                default:
                    break;
            }
        }
        return cap(switch (key.getValue()) {
            case InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT -> "Shift";
            case InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL -> "Ctrl";
            case InputConstants.KEY_LALT, InputConstants.KEY_RALT -> "Alt";
            default -> key.getDisplayName().getString();
        });
    }

    int width(Font font) {
        return kind == Kind.CAP ? Math.max(HEIGHT, font.width(name) + 2 * CAP_PADDING + 1) : MOUSE_WIDTH;
    }

    /** A pressed key is drawn pushed down and lit; a pressed mouse lit all over, the part in use left pale. */
    void draw(GuiGraphics graphics, Font font, int x, int y, boolean pressed) {
        int width = width(font);
        // The corners are left out, which rounds the outline off.
        graphics.fill(x + 1, y, x + width - 1, y + HEIGHT, EDGE);
        graphics.fill(x, y + 1, x + width, y + HEIGHT - 1, EDGE);
        if (kind == Kind.CAP) {
            // The edge shows two deep along the bottom, as the side of a key seen from above.
            int sunk = pressed ? 1 : 0;
            graphics.fill(x + 1, y + 1 + sunk, x + width - 1, y + HEIGHT - 2 + sunk, pressed ? LIT : FACE);
            graphics.drawString(font, name, x + (width - font.width(name) + 1) / 2, y + 1 + sunk, INK, false);
            return;
        }
        graphics.fill(x + 1, y + 1, x + width - 1, y + HEIGHT - 1, pressed ? LIT : FACE);
        graphics.fill(x + 1, y + 4, x + width - 1, y + 5, EDGE);
        graphics.fill(x + 4, y + 1, x + 5, y + 4, EDGE);
        int part = pressed ? FACE : LIT;
        switch (kind) {
            case LEFT -> graphics.fill(x + 1, y + 1, x + 4, y + 4, part);
            case RIGHT -> graphics.fill(x + 5, y + 1, x + 8, y + 4, part);
            // The wheel runs on past the buttons, so it is not taken for the left one.
            default -> graphics.fill(x + 3, y + 1, x + 6, y + 7, part);
        }
    }
}
