package io.github.izakyl.folkways.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scroller;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class Rows {

    public static final int CONTENT_WIDTH = 250;

    public static final int ROW_HEIGHT = 24;
    public static final int ICON_SIZE = 16;

    public static final int ACT_SIZE = 20;

    public static final int GAP = 4;

    /** Style class on a {@link #wideBox()}: content past its right edge is reached by scrolling sideways. */
    public static final String WIDE_SCROLL = "folkways_wide_scroll";

    private static final int HEADING_HEIGHT = 18;

    public static final int INK = 0xFF3B2A1A;
    public static final int QUIET = 0xFF7D6748;
    private static final int SECTION = 0xFF9A3324;
    private static final int TITLE = 0xFF2B1C12;

    private static final int METER_HEIGHT = 6;
    private static final int METER_BED = 0xFFC4AD7E;

    private Rows() {
    }

    // A list: full-width rows parted by hairlines, as tall as its rows and no taller.
    public static UIElement table() {
        return new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(1))
            .addClass("folkways_list");
    }

    // Along its foot a screen keeps a strip for the search bar and the key hints that nothing should cover.
    public static final float FOOT = 24f;

    // A grid narrower than this lays its cards one to a line: two would leave each too narrow for a name and its
    // buttons. Layout units are the interface's own, so a larger interface reaches it on a wider screen.
    private static final float TWO_COLUMNS = 360f;

    private static final String ONE_COLUMN = "folkways_one_column";

    // Cards two to a line, their outer edges flush with the page's; one to a line once the grid grows narrow.
    public static UIElement grid() {
        UIElement grid = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP)
                .widthPercent(100));
        grid.addEventListener(UIEvents.LAYOUT_CHANGED, event -> {
            boolean narrow = grid.getSizeWidth() < TWO_COLUMNS;
            if (narrow == grid.hasClass(ONE_COLUMN)) {
                return;
            }
            if (narrow) {
                grid.addClass(ONE_COLUMN);
            } else {
                grid.removeClass(ONE_COLUMN);
            }
            for (UIElement child : grid.getChildren()) {
                if (child instanceof Cell cell) {
                    place(cell, cell.span);
                }
            }
        });
        return grid;
    }

    public enum Span { LEFT, RIGHT, WHOLE }

    // A card's place in a grid, kept so the grid can lay it again when it changes how many go to a line.
    private static final class Cell extends UIElement {
        private Span span = Span.LEFT;
    }

    // Holds one card in a grid; place() says where on its line it sits.
    public static UIElement cell(UIElement card) {
        card.removeClass("folkways_row");
        card.addClass("folkways_card");
        card.layout(layout -> layout.paddingHorizontal(GAP));
        UIElement cell = new Cell().addChildren(card);
        cell.layout(layout -> layout.flexShrink(0).paddingBottom(GAP));
        return place(cell, Span.LEFT);
    }

    // The gutter between two cards is split between them, so each half pads only its inner side. In a grid laid one
    // to a line every card spans it whole, whatever side it was given.
    public static UIElement place(UIElement cell, Span span) {
        if (cell instanceof Cell kept) {
            kept.span = span;
        }
        UIElement grid = cell.getParent();
        Span laid = grid != null && grid.hasClass(ONE_COLUMN) ? Span.WHOLE : span;
        cell.layout(layout -> layout.widthPercent(laid == Span.WHOLE ? 100 : 50)
            .paddingRight(laid == Span.LEFT ? GAP / 2f : 0)
            .paddingLeft(laid == Span.RIGHT ? GAP / 2f : 0));
        return cell;
    }

    public static UIElement row() {
        UIElement row = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .widthPercent(100)
                .height(ROW_HEIGHT).flexShrink(0)
                .alignItems(AlignItems.CENTER)
                .gapAll(GAP));
        row.addClass("folkways_row");
        row.setOverflowVisible(false);
        return row;
    }

    public static UIElement icon(ItemStack stack) {
        return new UIElement()
            .layout(layout -> layout.width(ICON_SIZE).height(ICON_SIZE).flexShrink(0))
            .style(style -> style.background(new ItemStackTexture(stack)));
    }

    public static Label name(Component text) {
        Label label = new Label();
        label.setText(text);
        label.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        label.textStyle(style -> style.textWrap(TextWrap.HOVER_ROLL));
        return label;
    }

    public static Label value(Component text) {
        Label label = new Label();
        label.setText(text);
        label.layout(layout -> layout.widthAuto().flexShrink(1).minWidth(0));
        label.textStyle(style -> style.textWrap(TextWrap.NONE).adaptiveWidth(true));
        return label;
    }

    public static Label text(Component text) {
        Label label = new Label();
        label.setText(text);
        label.layout(layout -> layout.widthPercent(100).flexShrink(0).minWidth(0));
        label.textStyle(style -> style.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        return label;
    }

    // Dim text: help, empty states, what a figure counts.
    public static Label note(Component text) {
        Label label = text(text);
        label.textStyle(style -> style.textColor(QUIET));
        return label;
    }

    // What stands in the way: work that cannot go on, and why.
    public static Label alarm(Component text) {
        Label label = text(text);
        label.textStyle(style -> style.textColor(SECTION));
        return label;
    }

    // A section inside a page.
    public static UIElement heading(Component text) {
        return headed(text, SECTION, HEADING_HEIGHT);
    }

    // The page's own name; whatever is added after it sits at the right end of the line.
    public static UIElement title(Component text) {
        UIElement line = headed(text.copy().withStyle(ChatFormatting.BOLD), TITLE, ACT_SIZE);
        line.layout(layout -> layout.gapAll(GAP + 2));
        return line;
    }

    // A quiet label over a group of navigation entries.
    public static UIElement group(Component text) {
        return headed(text, QUIET, HEADING_HEIGHT);
    }

    private static UIElement headed(Component text, int color, int height) {
        Label label = new Label();
        label.setText(text);
        label.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        label.textStyle(style -> style.textWrap(TextWrap.HIDE).textColor(color));
        UIElement line = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .widthPercent(100)
                .height(height).flexShrink(0)
                .alignItems(AlignItems.CENTER))
            .addChildren(label);
        line.setOverflowVisible(false);
        return line;
    }

    // "Label value" with the label dimmed, small enough to ride a title line.
    public static Label figure(Component label, Component value) {
        return value(label.copy().withColor(QUIET & 0xFFFFFF).append(" ").append(value));
    }

    public static Button act() {
        Button press = new Button().noText();
        press.layout(layout -> layout.width(ACT_SIZE).height(ACT_SIZE).flexShrink(0).paddingAll(0));
        return press;
    }

    public static UIElement actIcon(ItemStack stack) {
        return new UIElement()
            .layout(layout -> layout.widthPercent(100).heightPercent(100))
            .style(style -> style.background(new ItemStackTexture(stack)));
    }

    public static UIElement meter() {
        UIElement fill = new UIElement()
            .layout(layout -> layout.heightPercent(100).widthPercent(0));
        return new UIElement()
            .layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0)
                .height(METER_HEIGHT).alignSelf(AlignItems.CENTER))
            .style(style -> style.background(new ColorRectTexture(METER_BED)))
            .addChildren(fill);
    }

    public static void meterFill(UIElement bed, float percent, int color) {
        if (bed.getChildren().isEmpty()) {
            return;
        }
        UIElement fill = bed.getChildren().get(0);
        float clamped = Math.max(0f, Math.min(100f, percent));
        fill.layout(layout -> layout.widthPercent(clamped));
        fill.style(style -> style.background(new ColorRectTexture(color)));
    }

    public static UIElement strip() {
        return new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .widthPercent(100)
                .alignItems(AlignItems.CENTER)
                .gapAll(GAP + 2));
    }

    // Pushes whatever follows it to the far end of a strip, row or page.
    public static UIElement spacer() {
        return new UIElement().layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0).minHeight(0));
    }

    // Buttons gathered at the right end of their line, the one that commits last.
    public static UIElement actions(UIElement... buttons) {
        return strip().addChildren(spacer()).addChildren(buttons);
    }

    public static UIElement page() {
        return new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .widthPercent(100).gapAll(5));
    }

    public static ScrollerView box() {
        ScrollerView view = new ScrollerView();
        view.scrollerStyle(style -> style
            .mode(ScrollerMode.VERTICAL)
            .verticalScrollDisplay(ScrollDisplay.AUTO)
            .horizontalScrollDisplay(ScrollDisplay.NEVER)
            .minScrollPixel(ROW_HEIGHT)
            .maxScrollPixel(ROW_HEIGHT));
        view.viewPort(port -> port.layout(layout -> layout
            .widthPercent(100).alignItems(AlignItems.STRETCH)));
        view.viewContainer(container -> container.layout(layout -> layout
            .flexDirection(FlexDirection.COLUMN).widthPercent(100).heightAuto()));
        return tracked(view);
    }

    public static ScrollerView wideBox() {
        ScrollerView view = new ScrollerView();
        view.scrollerStyle(style -> style
            .mode(ScrollerMode.BOTH)
            .verticalScrollDisplay(ScrollDisplay.AUTO)
            .horizontalScrollDisplay(ScrollDisplay.AUTO)
            .minScrollPixel(ROW_HEIGHT)
            .maxScrollPixel(ROW_HEIGHT));
        view.viewPort(port -> port.layout(layout -> layout
            .widthPercent(100).minWidth(0).flexShrink(1).alignItems(AlignItems.FLEX_START)));
        view.addClass(WIDE_SCROLL);
        view.viewContainer(container -> container.layout(layout -> layout
            .flexDirection(FlexDirection.COLUMN).widthAuto().heightAuto()));
        return tracked(view);
    }

    // LDLib2 gives the holder a scroll bar is sized against a Yoga "flex: 1", which its Taffy layout does not read,
    // so the holder keeps the bar's own width and a bar sized at its share of the track shrinks to a sliver. The
    // holder is made to grow along the track. Inside a page's window even a growing or full-width holder in the
    // sideways track is laid at the bar's few pixels, so that one is given the track's width outright.
    private static ScrollerView tracked(ScrollerView view) {
        for (Scroller scroller : List.of(view.horizontalScroller, view.verticalScroller)) {
            for (UIElement holder : scroller.scrollContainer.getChildren()) {
                holder.layout(layout -> layout.flexGrow(1));
            }
        }
        UIElement track = view.horizontalScroller.scrollContainer;
        track.layout(layout -> layout.flexDirection(FlexDirection.ROW));
        track.addEventListener(UIEvents.LAYOUT_CHANGED, event -> {
            float room = track.getContentWidth();
            for (UIElement holder : track.getChildren()) {
                if (room > 0 && holder.getSizeWidth() != room) {
                    holder.layout(layout -> layout.width(room));
                }
            }
        });
        return view;
    }

    public static <T extends UIElement> T fills(T element) {
        element.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        return element;
    }
}
