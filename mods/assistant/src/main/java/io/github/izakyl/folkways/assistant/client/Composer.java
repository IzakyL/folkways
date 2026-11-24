package io.github.izakyl.folkways.assistant.client;

import com.lowdragmc.lowdraglib2.gui.LDLibFonts;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

/**
 * The chat's input: a field whose words wrap onto as many lines as they take, up to {@link #MOST} before it scrolls.
 * Enter sends, Shift+Enter starts a new line. Editing, the clipboard, undo and typed (IME) text stay the field's own;
 * only how the words are laid out, pointed at and stepped through by line is drawn here.
 */
final class Composer extends TextField {

    static final int MOST = 6;

    private static final String CLOSING = "，。、；：！？）」』”’》〉】,.;:!?)]}";

    private Runnable submit = () -> {
    };

    // Each shown line as [start, end) in the raw text; a line break itself belongs to no line.
    private List<int[]> lines;

    private String wrappedText;

    private float wrappedWidth = -1;

    private int top;

    private int shown;

    // Where Up and Down aim, kept across a run of them so a short line does not pull the cursor left for good.
    private float goal = -1;

    private record Anchor(int at) {
    }

    Composer onSubmit(Runnable submit) {
        this.submit = submit;
        return this;
    }

    private float scale() {
        return getTextFieldStyle().fontSize() / 9f;
    }

    private float lineHeight() {
        return getTextFieldStyle().fontSize() + 1;
    }

    private Style style() {
        return Style.EMPTY.withFont(getTextFieldStyle().font());
    }

    private float width(String text) {
        return getFont().getSplitter().stringWidth(FormattedText.of(text, style())) * scale();
    }

    private List<int[]> lines() {
        String text = getRawText();
        float width = getContentWidth();
        if (lines == null || !text.equals(wrappedText) || width != wrappedWidth) {
            lines = wrap(text, width);
            wrappedText = text;
            wrappedWidth = width;
        }
        return lines;
    }

    private List<int[]> wrap(String text, float width) {
        List<int[]> wrapped = new ArrayList<>();
        int room = Math.max(1, (int) (width / scale()));
        int start = 0;
        while (true) {
            int brk = text.indexOf('\n', start);
            int end = brk < 0 ? text.length() : brk;
            int from = start;
            while (width > 0 && from < end && width(text.substring(from, end)) > width) {
                int fit = from + getFont().getSplitter().plainIndexAtWidth(text.substring(from, end), room, style());
                if (fit <= from) {
                    fit = text.offsetByCodePoints(from, 1);
                }
                // Break after the last space that fits, so words stay whole; text without spaces breaks anywhere.
                int space = text.lastIndexOf(' ', fit - 1);
                if (space >= from && space + 1 < end && space + 1 > from) {
                    fit = space + 1;
                } else if (fit < end && CLOSING.indexOf(text.charAt(fit)) >= 0
                        && text.offsetByCodePoints(from, 1) < fit) {
                    // A closing mark never opens a line: the character before it goes down with it.
                    fit = text.offsetByCodePoints(fit, -1);
                }
                wrapped.add(new int[] {from, fit});
                from = fit;
            }
            wrapped.add(new int[] {from, end});
            if (brk < 0) {
                return wrapped;
            }
            start = brk + 1;
        }
    }

    private int lineOf(int at) {
        List<int[]> all = lines();
        int line = 0;
        while (line + 1 < all.size() && all.get(line + 1)[0] <= at) {
            line++;
        }
        return line;
    }

    private float xOf(int at) {
        int[] line = lines().get(lineOf(at));
        return width(getRawText().substring(line[0], Math.max(line[0], Math.min(at, line[1]))));
    }

    // The nearest gap between characters on a line to a distance along it.
    private int atX(int line, float x) {
        int[] span = lines().get(line);
        String text = getRawText();
        int at = span[0];
        float before = 0;
        while (at < span[1]) {
            int next = text.offsetByCodePoints(at, 1);
            float after = width(text.substring(span[0], next));
            if (x < (before + after) / 2) {
                break;
            }
            at = next;
            before = after;
        }
        return at;
    }

    private int atMouse(float mouseX, float mouseY) {
        Vector2f local = getLocalMouse(mouseX, mouseY);
        int line = (int) Math.floor((local.y - getContentY()) / lineHeight()) + top;
        line = Math.max(0, Math.min(lines().size() - 1, line));
        return atX(line, local.x - getContentX());
    }

    // Grows or shrinks to the lines the words take, and keeps the cursor's line in sight.
    private void refresh() {
        if (getContentWidth() <= 0) {
            return;
        }
        int count = lines().size();
        int visible = Math.max(1, Math.min(MOST, count));
        if (visible != shown) {
            shown = visible;
            float padding = getSizeHeight() - getContentHeight();
            layout(layout -> layout.height(padding + visible * lineHeight()));
        }
        int line = lineOf(getCursorPos());
        if (line < top) {
            top = line;
        } else if (line >= top + visible) {
            top = line - visible + 1;
        }
        top = Math.max(0, Math.min(top, count - visible));
    }

    @Override
    protected void onRawTextUpdate() {
        super.onRawTextUpdate();
        if (getRawText() != null) {
            refresh();
        }
    }

    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        refresh();
    }

    @Override
    public void insertText(String text) {
        super.insertText(text.replace("\r\n", "\n").replace('\r', '\n'));
    }

    @Override
    protected void onMouseDown(UIEvent event) {
        if (event.button != 0) {
            return;
        }
        goal = -1;
        int at = atMouse(event.x, event.y);
        int anchor = isShiftDown() ? getSelectionStart() : at;
        setCursor(at);
        setSelection(anchor, at);
        startDrag(new Anchor(anchor), null);
    }

    @Override
    protected void onDragSource(UIEvent event) {
        if (event.dragHandler.draggingObject instanceof Anchor anchor) {
            int at = atMouse(event.x, event.y);
            setCursor(at);
            setSelection(anchor.at(), at);
        }
    }

    @Override
    protected void onMouseWheel(UIEvent event) {
        int count = lines().size();
        if (count > shown) {
            top = Math.max(0, Math.min(count - shown, top + (event.deltaY > 0 ? -1 : 1)));
            event.stopPropagation();
        }
    }

    @Override
    protected void onKeyDown(UIEvent event) {
        int key = event.keyCode;
        if (key != GLFW.GLFW_KEY_UP && key != GLFW.GLFW_KEY_DOWN) {
            goal = -1;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (isShiftDown()) {
                if (isEditable()) {
                    insertText("\n");
                }
            } else {
                submit.run();
            }
            event.stopPropagation();
            return;
        }
        int line = lineOf(getCursorPos());
        int to;
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            int next = line + (key == GLFW.GLFW_KEY_UP ? -1 : 1);
            if (goal < 0) {
                goal = xOf(getCursorPos());
            }
            to = next < 0 ? 0 : next >= lines().size() ? getRawText().length() : atX(next, goal);
        } else if ((key == GLFW.GLFW_KEY_HOME || key == GLFW.GLFW_KEY_END) && !event.isCtrlDown()) {
            int[] span = lines().get(line);
            to = key == GLFW.GLFW_KEY_HOME ? span[0] : span[1];
        } else {
            super.onKeyDown(event);
            return;
        }
        int anchor = isShiftDown() ? getSelectionStart() : to;
        setCursor(to);
        setSelection(anchor, to);
        event.stopPropagation();
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        refresh();
        float x = getContentX();
        float y = getContentY();
        float height = lineHeight();
        float size = getTextFieldStyle().fontSize();
        String text = getRawText();
        Font font = getFont();
        List<int[]> all = lines();
        int last = Math.min(all.size(), top + Math.max(1, shown));
        RenderSystem.depthMask(false);
        if (text.isEmpty()) {
            draw(context, font, getTextFieldStyle().placeholder().copy().withStyle(style()), x, y + 0.5f, Rows.QUIET,
                    false);
        }
        for (int line = top; line < last; line++) {
            int[] span = all.get(line);
            draw(context, font, Component.literal(text.substring(span[0], span[1])).withStyle(style()), x,
                    y + (line - top) * height + 0.5f,
                    isError() ? getTextFieldStyle().errorColor() : getTextFieldStyle().textColor(),
                    getTextFieldStyle().textShadow());
        }
        RenderSystem.depthMask(true);
        if (!isFocused()) {
            return;
        }
        int from = Math.min(getSelectionStart(), getSelectionEnd());
        int to = Math.max(getSelectionStart(), getSelectionEnd());
        if (from != to) {
            for (int line = top; line < last; line++) {
                int[] span = all.get(line);
                int a = Math.max(from, span[0]);
                int b = Math.min(to, span[1]);
                if (a < b) {
                    float left = width(text.substring(span[0], a));
                    float right = width(text.substring(span[0], b));
                    DrawerHelper.drawSolidRect(context.graphics, RenderType.guiTextHighlight(), x + left,
                            y + (line - top) * height + 0.5f, right - left, size, 0xFF0000FF);
                }
            }
        }
        int cursor = lineOf(getCursorPos());
        if (cursor >= top && cursor < last && System.currentTimeMillis() % 1000L < 500L) {
            DrawerHelper.drawSolidRect(context.graphics, x + xOf(getCursorPos()), y + (cursor - top) * height + 0.5f,
                    1, size, getTextFieldStyle().cursorColor());
        }
    }

    private void draw(GUIContext context, Font font, Component line, float x, float y, int color, boolean shadow) {
        float scale = scale();
        context.pose.pushPose();
        context.pose.translate(x, y, 0);
        context.pose.scale(scale, scale, 1);
        LDLibFonts.drawText(context.graphics, font, line, 0, 0, color, shadow);
        context.pose.popPose();
    }
}
