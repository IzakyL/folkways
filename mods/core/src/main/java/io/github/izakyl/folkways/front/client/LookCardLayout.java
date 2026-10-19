package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.front.api.ui.Signs;
import io.github.izakyl.folkways.front.client.ColonyLookCard.Row;
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
    private static final int ITEM_GAP = 1;
    private static final int GOODS_GAP = 5;
    private static final int GOODS_ROW = ITEM_SIZE + 2;
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
            case Row.Signs signs -> GOODS_ROW;
            case Row.Gauge gauge -> GAUGE_PITCH;
        };
    }

    private static int ink(Row row) {
        return switch (row) {
            case Row.Text text -> TEXT_INK;
            case Row.Bar bar -> PROGRESS_HEIGHT;
            case Row.Goods goods -> ITEM_SIZE;
            case Row.Signs signs -> Signs.HEIGHT;
            case Row.Gauge gauge -> GaugeIcons.ICON;
        };
    }

    private static int goodsWidth(Font font, Row.Goods goods) {
        int width = 0;
        for (String count : goods.counts()) {
            width += ITEM_SIZE + ITEM_GAP + font.width(count) + GOODS_GAP;
        }
        return Math.max(0, width - GOODS_GAP);
    }

    /** Each stack's icon with its count beside it, the count's ink centred on the icon. */
    private static void goods(List<Piece> pieces, Font font, Row.Goods goods, int y) {
        int x = 0;
        for (int at = 0; at < goods.stacks().size(); at++) {
            pieces.add(new Piece.Item(x, y, ITEM_SIZE, goods.stacks().get(at)));
            x += ITEM_SIZE + ITEM_GAP;
            String count = goods.counts().get(at);
            pieces.add(new Piece.Text(x, y + (ITEM_SIZE - TEXT_INK) / 2, count, ColonyLookCard.TITLE_COLOR));
            x += font.width(count) + GOODS_GAP;
        }
    }

    private static void signs(List<Piece> pieces, Font font, Row.Signs signs, int y) {
        for (Signs.Placed placed : Signs.lay(font, signs.signs()).placed()) {
            pieces.add(switch (placed) {
                case Signs.Placed.Glyph glyph ->
                    new Piece.Icon(glyph.x(), y + glyph.y(), glyph.width(), glyph.height(), glyph.sprite());
                case Signs.Placed.Item item -> new Piece.Item(item.x(), y + item.y(), ITEM_SIZE, item.stack());
                case Signs.Placed.Text text -> new Piece.Text(text.x(), y + text.y(), text.text(), text.color());
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
