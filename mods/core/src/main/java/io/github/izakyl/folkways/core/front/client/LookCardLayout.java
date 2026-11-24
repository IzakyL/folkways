package io.github.izakyl.folkways.core.front.client;

import io.github.izakyl.folkways.core.front.api.ui.Signs;
import io.github.izakyl.folkways.core.front.client.ColonyLookCard.Row;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Places a card's rows in card-local units, top-left at 0,0: words, goods, the job and its progress down the left,
 * vitals stacked small on the right, the shorter column centred against the taller.
 */
final class LookCardLayout {
    private static final int LINE_HEIGHT = 10;
    private static final int TEXT_INK = 8;
    private static final int PROGRESS_HEIGHT = 2;
    private static final int PROGRESS_ROW = PROGRESS_HEIGHT + 2;
    private static final int GAUGE_PITCH = GaugeIcons.ICON;
    private static final int COLUMN_GAP = 8;
    static final int ITEM_SIZE = 16;
    private static final int GOODS_GAP = 5;
    private static final int GOODS_ROW = ITEM_SIZE + 2;
    static final int GRID_COLUMNS = 4;
    static final int PROGRESS_TRACK = 0xFF3B3B3B;
    static final int PROGRESS_FILL = 0xFFDDDDDD;

    private LookCardLayout() {
    }

    record Laid(int width, int height, List<Piece> pieces) {
    }

    sealed interface Piece {

        record Text(int x, int y, String text, int color) implements Piece {
        }

        record Fill(int x0, int y0, int x1, int y1, int color) implements Piece {
        }

        record Icon(int x, int y, int width, int height, ResourceLocation sprite) implements Piece {
        }

        record Item(int x, int y, int size, ItemStack stack) implements Piece {
        }

        /** An item's count, at the item's top-left x,y, drawn small over its corner in front of the item. */
        record Count(int x, int y, String text, int color) implements Piece {
        }
    }

    static Laid of(Font font, List<Row> rows) {
        List<Row> words = new ArrayList<>();
        List<Row.Gauge> gauges = new ArrayList<>();
        for (Row row : rows) {
            if (row instanceof Row.Gauge gauge) {
                gauges.add(gauge);
            } else {
                words.add(row);
            }
        }

        int leftWidth = 0;
        int leftHeight = 0;
        for (Row row : words) {
            leftWidth = Math.max(leftWidth, switch (row) {
                case Row.Text text -> font.width(text.text());
                case Row.Goods goods -> goodsWidth(font, goods);
                case Row.Grid grid -> gridWidth(font, grid);
                case Row.Signs signs -> Signs.lay(font, signs.signs()).width();
                case Row.Bar bar -> 0;
                case Row.Gauge gauge -> throw new IllegalStateException("gauges go on the right");
            });
            leftHeight += pitch(row);
        }
        if (!words.isEmpty()) {
            leftHeight -= pitch(words.getLast()) - ink(words.getLast());
        }
        int rightHeight = gauges.isEmpty() ? 0 : gauges.size() * GAUGE_PITCH - (GAUGE_PITCH - GaugeIcons.ICON);
        int height = Math.max(leftHeight, rightHeight);
        int rightX = gauges.isEmpty() ? leftWidth : words.isEmpty() ? 0 : leftWidth + COLUMN_GAP;
        int width = gauges.isEmpty() ? leftWidth : rightX + GaugeIcons.WIDTH;

        List<Piece> pieces = new ArrayList<>();
        int y = (height - leftHeight) / 2;
        for (Row row : words) {
            switch (row) {
                case Row.Text text -> {
                    pieces.add(new Piece.Text(0, y, text.text(), text.color()));
                    y += LINE_HEIGHT;
                }
                case Row.Bar bar -> {
                    bar(pieces, 0, y, leftWidth, PROGRESS_HEIGHT, bar.fill(), PROGRESS_FILL, PROGRESS_TRACK);
                    y += PROGRESS_ROW;
                }
                case Row.Goods goods -> {
                    goods(pieces, font, goods, y);
                    y += GOODS_ROW;
                }
                case Row.Grid grid -> {
                    grid(pieces, font, grid, y);
                    y += pitch(grid);
                }
                case Row.Signs signs -> {
                    signs(pieces, font, signs, y);
                    y += GOODS_ROW;
                }
                case Row.Gauge gauge -> throw new IllegalStateException("gauges go on the right");
            }
        }

        y = (height - rightHeight) / 2;
        for (Row.Gauge gauge : gauges) {
            pieces.add(new Piece.Icon(rightX, y, GaugeIcons.ICON, GaugeIcons.ICON, gauge.meter().icon()));
            int barX = rightX + GaugeIcons.ICON + GaugeIcons.GAP;
            int barY = y + (GaugeIcons.ICON - GaugeIcons.BAR_HEIGHT + 1) / 2;
            bar(pieces, barX, barY, GaugeIcons.BAR_WIDTH, GaugeIcons.BAR_HEIGHT, gauge.fill(),
                gauge.meter().color(), GaugeIcons.TRACK_COLOR);
            y += GAUGE_PITCH;
        }
        return new Laid(width, height, List.copyOf(pieces));
    }

    private static int pitch(Row row) {
        return switch (row) {
            case Row.Text text -> LINE_HEIGHT;
            case Row.Bar bar -> PROGRESS_ROW;
            case Row.Goods goods -> GOODS_ROW;
            case Row.Grid grid -> gridRows(grid) * GOODS_ROW;
            case Row.Signs signs -> GOODS_ROW;
            case Row.Gauge gauge -> GAUGE_PITCH;
        };
    }

    private static int ink(Row row) {
        return switch (row) {
            case Row.Text text -> TEXT_INK;
            case Row.Bar bar -> PROGRESS_HEIGHT;
            case Row.Goods goods -> ITEM_SIZE;
            case Row.Grid grid -> (gridRows(grid) - 1) * GOODS_ROW + ITEM_SIZE;
            case Row.Signs signs -> Signs.HEIGHT;
            case Row.Gauge gauge -> GaugeIcons.ICON;
        };
    }

    private static int goodsWidth(Font font, Row.Goods goods) {
        int width = 0;
        for (String count : goods.counts()) {
            width += ITEM_SIZE + GOODS_GAP;
        }
        return Math.max(0, width - GOODS_GAP);
    }

    private static int gridRows(Row.Grid grid) {
        return Math.max(1, (grid.stacks().size() + GRID_COLUMNS - 1) / GRID_COLUMNS);
    }

    private static int gridCell(Font font, Row.Grid grid) {
        return ITEM_SIZE;
    }

    private static int gridWidth(Font font, Row.Grid grid) {
        int columns = Math.min(GRID_COLUMNS, grid.stacks().size());
        return columns == 0 ? 0 : columns * (gridCell(font, grid) + GOODS_GAP) - GOODS_GAP;
    }

    /** Goods in rows of {@link #GRID_COLUMNS}, each icon with its count in its corner. */
    private static void grid(List<Piece> pieces, Font font, Row.Grid grid, int y) {
        int cell = gridCell(font, grid) + GOODS_GAP;
        for (int at = 0; at < grid.stacks().size(); at++) {
            int x = at % GRID_COLUMNS * cell;
            int top = y + at / GRID_COLUMNS * GOODS_ROW;
            ware(pieces, font, x, top, grid.stacks().get(at), grid.counts().get(at));
        }
    }

    /** Each stack's icon with its count over its corner. */
    private static void goods(List<Piece> pieces, Font font, Row.Goods goods, int y) {
        int x = 0;
        for (int at = 0; at < goods.stacks().size(); at++) {
            String count = goods.counts().get(at);
            ware(pieces, font, x, y, goods.stacks().get(at), count);
            x += ITEM_SIZE + GOODS_GAP;
        }
    }

    // An icon and its count, placed as Signs places a ware's.
    private static void ware(List<Piece> pieces, Font font, int x, int y, ItemStack stack, String count) {
        pieces.add(new Piece.Item(x, y, ITEM_SIZE, stack));
        if (!count.isEmpty()) {
            pieces.add(new Piece.Count(x, y, count, ColonyLookCard.TITLE_COLOR));
        }
    }

    private static void signs(List<Piece> pieces, Font font, Row.Signs signs, int y) {
        for (Signs.Placed placed : Signs.lay(font, signs.signs()).placed()) {
            pieces.add(switch (placed) {
                case Signs.Placed.Glyph glyph ->
                    new Piece.Icon(glyph.x(), y + glyph.y(), glyph.width(), glyph.height(), Signs.onDark(glyph.sprite()));
                case Signs.Placed.Item item -> new Piece.Item(item.x(), y + item.y(), ITEM_SIZE, item.stack());
                case Signs.Placed.Text text -> new Piece.Text(text.x(), y + text.y(), text.text(), text.color());
                case Signs.Placed.Count count -> new Piece.Count(count.x(), y + count.y(), count.text(), count.color());
            });
        }
    }

    /** The filled part and the track beside it, never overlapping, so neither depends on draw order. */
    private static void bar(List<Piece> pieces, int x, int y, int width, int height, float fill, int color, int track) {
        int split = x + Math.round(width * Math.clamp(fill, 0.0F, 1.0F));
        if (split > x) {
            pieces.add(new Piece.Fill(x, y, split, y + height, color));
        }
        if (split < x + width) {
            pieces.add(new Piece.Fill(split, y, x + width, y + height, track));
        }
    }
}
