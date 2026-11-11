package io.github.izakyl.folkways.core.front.client;

import io.github.izakyl.folkways.core.front.api.notice.DebugText;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.front.api.ui.Signs;
import io.github.izakyl.folkways.core.front.api.ui.Signs.Sign;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

/** Wraps debug words and pictures within the panel, preserving component styles. */
final class DebugCardLayout {
    private final Font font;
    private final int width;
    private final List<Words> words = new ArrayList<>();
    private final List<Picture> pictures = new ArrayList<>();
    private int height;

    private record Words(int x, int y, FormattedCharSequence text, int color) {
    }

    private record Badge(int y, ResourceLocation sprite) {
    }

    /** Unstyled words, the labels of a debug line; styled arguments carry their own colour. */
    private static final int LABEL_COLOR = 0xFF000000 | DebugText.LABEL;
    private static final int ICON = 10;
    private static final int ICON_GAP = 2;
    private final List<Badge> badges = new ArrayList<>();

    private record Picture(int y, float scale, LookCardLayout.Laid laid) {
    }

    private DebugCardLayout(Font font, int width) {
        this.font = font;
        this.width = width;
    }

    static DebugCardLayout of(Font font, List<Line> lines, int width) {
        var layout = new DebugCardLayout(font, width);
        for (int index = 0; index < lines.size(); index++) {
            Line line = lines.get(index);
            int color = index == 0 ? ColonyLookCard.TITLE_COLOR : LABEL_COLOR;
            if (line instanceof Line.Text text && text.after().isEmpty()) {
                if (text.component().getStyle().isBold()) {
                    layout.height += 4;
                }
                var icon = DebugText.icon(text.component());
                if (icon.isPresent()) {
                    layout.badges.add(new Badge(layout.height - 1, icon.get()));
                    layout.text(text.component(), color, ICON + ICON_GAP);
                } else {
                    layout.text(text.component(), color);
                }
            } else if (line instanceof Line.Goods goods && goods.tint() != 0) {
                // Goods in a state: a mark of its colour first, and each count in it.
                int tint = 0xFF000000 | goods.tint();
                for (var row : ColonyLookCard.rows(List.of(line))) {
                    if (row instanceof ColonyLookCard.Row.Goods stacks) {
                        List<Sign> signs = new ArrayList<>();
                        signs.add(new Sign.Word("■", tint));
                        for (int at = 0; at < stacks.stacks().size(); at++) {
                            signs.add(new Sign.Ware(stacks.stacks().get(at), stacks.counts().get(at)));
                        }
                        layout.signs(signs, tint);
                    }
                }
            } else {
                for (var row : ColonyLookCard.rows(List.of(line))) {
                    layout.row(row);
                }
            }
        }
        return layout;
    }

    private void text(Component component, int color) {
        text(component, color, 0);
    }

    // Styled spans keep their own colour when drawn; color only inks the unstyled text.
    private void text(Component component, int color, int indent) {
        for (var part : font.split(component, Math.max(1, width - indent))) {
            words.add(new Words(indent, height, part, color));
            height += font.lineHeight + 1;
        }
    }

    private void row(ColonyLookCard.Row row) {
        switch (row) {
            case ColonyLookCard.Row.Text text -> text(Component.literal(text.text()), text.color());
            case ColonyLookCard.Row.Goods goods -> {
                List<Sign> signs = new ArrayList<>();
                for (int index = 0; index < goods.stacks().size(); index++) {
                    signs.add(new Sign.Ware(goods.stacks().get(index), goods.counts().get(index)));
                }
                signs(signs, 0);
            }
            case ColonyLookCard.Row.Signs signs -> signs(signs.signs(), 0);
            default -> picture(LookCardLayout.of(font, List.of(row)));
        }
    }

    private void signs(List<Sign> signs, int tint) {
        List<Sign> line = new ArrayList<>();
        for (Sign sign : signs) {
            var candidate = new ArrayList<>(line);
            candidate.add(sign);
            if (LookCardLayout.of(font, List.of(new ColonyLookCard.Row.Signs(candidate))).width() > width) {
                flush(line, tint);
            }
            if (sign instanceof Sign.Word word && font.width(word.text()) > width) {
                text(Component.literal(word.text()), word.color());
            } else {
                line.add(sign);
            }
        }
        flush(line, tint);
    }

    private void flush(List<Sign> signs, int tint) {
        if (!signs.isEmpty()) {
            var laid = LookCardLayout.of(font, List.of(new ColonyLookCard.Row.Signs(signs)));
            picture(tint == 0 ? laid : tinted(laid, tint));
            signs.clear();
        }
    }

    // The counts, white beside their items, in the goods' state colour instead.
    private static LookCardLayout.Laid tinted(LookCardLayout.Laid laid, int tint) {
        return new LookCardLayout.Laid(laid.width(), laid.height(), laid.pieces().stream()
            .map(piece -> piece instanceof LookCardLayout.Piece.Text text && text.color() == Signs.COUNT_COLOR
                ? new LookCardLayout.Piece.Text(text.x(), text.y(), text.text(), tint) : piece).toList());
    }

    private void picture(LookCardLayout.Laid laid) {
        float scale = laid.width() > width ? (float) width / laid.width() : 1.0F;
        pictures.add(new Picture(height, scale, laid));
        height += (int) Math.ceil(laid.height() * scale) + 2;
    }

    int height() {
        return height;
    }

    void draw(GuiGraphics graphics) {
        for (Badge badge : badges) {
            graphics.blitSprite(badge.sprite(), 0, badge.y(), ICON, ICON);
        }
        for (Words word : words) {
            graphics.drawString(font, word.text(), word.x(), word.y(), word.color());
        }
        for (Picture picture : pictures) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, picture.y(), 0);
            graphics.pose().scale(picture.scale(), picture.scale(), 1.0F);
            for (var piece : picture.laid().pieces()) {
                switch (piece) {
                    case LookCardLayout.Piece.Text text ->
                        graphics.drawString(font, text.text(), text.x(), text.y(), text.color());
                    case LookCardLayout.Piece.Fill fill ->
                        graphics.fill(fill.x0(), fill.y0(), fill.x1(), fill.y1(), fill.color());
                    case LookCardLayout.Piece.Icon icon ->
                        graphics.blitSprite(icon.sprite(), icon.x(), icon.y(), icon.width(), icon.height());
                    case LookCardLayout.Piece.Item item -> graphics.renderItem(item.stack(), item.x(), item.y());
                }
            }
            graphics.pose().popPose();
        }
    }
}
