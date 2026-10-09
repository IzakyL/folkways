package io.github.izakyl.folkways.front.api.ui;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A sentence as it is drawn: each token made a sign, a glyph whose sprite is missing made its words, and the signs
 * laid in a line {@link #HEIGHT} tall, each centred on the items' height, an item's count set low beside it as a
 * subscript with its first column tucked under the item's corner.
 */
public final class Signs {
    public static final int ITEM_SIZE = 16;
    public static final int HEIGHT = ITEM_SIZE + 1;
    public static final int WORD_COLOR = 0xFFAAAAAA;
    public static final int COUNT_COLOR = 0xFFFFFFFF;
    private static final int TEXT_INK = 8;
    private static final int GAP = 2;
    // Items side by side, with no plus between them, stand further apart so a count is not read as the next item's.
    private static final int WARE_GAP = 5;
    private static final int SUBSCRIPT_TUCK = 1;
    private static final int SUBSCRIPT_DROP = 1;
    private static final String MORE = "…";
    private static final Sign PLUS = new Sign.Mark(ours("glyph/plus"), 6, 6);
    private static final Sign YIELDS = new Sign.Mark(ours("glyph/yields"), 8, 6);

    private Signs() {
    }

    /** One picture in a line of them: a glyph, an item with its count set low beside it, or a word. */
    public sealed interface Sign {

        record Mark(ResourceLocation sprite, int width, int height) implements Sign {
        }

        record Ware(ItemStack stack, String count) implements Sign {
        }

        record Word(String text, int color) implements Sign {
        }
    }

    public record Laid(int width, List<Placed> placed) {
    }

    /** A sign put down, top-left at x,y in the line. */
    public sealed interface Placed {

        record Glyph(int x, int y, int width, int height, ResourceLocation sprite) implements Placed {
        }

        record Item(int x, int y, ItemStack stack) implements Placed {
        }

        record Text(int x, int y, String text, int color) implements Placed {
        }
    }

    public static List<Sign> of(Sentence sentence) {
        return of(sentence, WORD_COLOR);
    }

    /** The sentence's signs, its words in {@code ink}; an item unknown here is left out. */
    public static List<Sign> of(Sentence sentence, int ink) {
        List<Sign> signs = new ArrayList<>();
        for (Sentence.Token token : sentence.tokens()) {
            switch (token) {
                case Sentence.Token.Glyph glyph -> {
                    Optional<SpriteContents> drawn = drawn(glyph.sprite());
                    if (drawn.isPresent()) {
                        signs.add(new Sign.Mark(glyph.sprite(), drawn.get().width(), drawn.get().height()));
                    } else {
                        glyph.otherwise().ifPresent(words -> signs.add(word(words, ink)));
                    }
                }
                case Sentence.Token.Ware ware -> {
                    Item item = BuiltInRegistries.ITEM.get(ware.item());
                    if (item != Items.AIR) {
                        signs.add(new Sign.Ware(new ItemStack(item), ware.count() > 1L ? compact(ware.count()) : ""));
                    }
                }
                case Sentence.Token.Plus plus -> signs.add(PLUS);
                case Sentence.Token.Yields yields -> signs.add(YIELDS);
                case Sentence.Token.Word word -> signs.add(word(word.notice(), ink));
            }
        }
        return List.copyOf(signs);
    }

    public static String compact(long count) {
        if (count < 1_000L) {
            return Long.toString(count);
        }
        if (count < 10_000L) {
            return String.format(Locale.ROOT, "%.1fk", count / 1_000.0D);
        }
        return count / 1_000L + "k";
    }

    public static Laid lay(Font font, List<Sign> signs) {
        return lay(font, signs, Integer.MAX_VALUE);
    }

    /**
     * The signs laid in no more than {@code room} across. Signs that would run past it are left off the end, and an
     * ellipsis stands in their place where it fits.
     */
    public static Laid lay(Font font, List<Sign> signs, int room) {
        int shown = signs.size();
        if (width(font, signs, shown) > room) {
            int more = font.width(MORE);
            do {
                shown--;
            } while (shown > 0 && width(font, signs, shown) + GAP + more > room);
        }
        List<Placed> placed = new ArrayList<>();
        int x = 0;
        Sign before = null;
        for (Sign sign : signs.subList(0, shown)) {
            x += gap(before, sign);
            before = sign;
            switch (sign) {
                case Sign.Mark mark ->
                    placed.add(new Placed.Glyph(x, (ITEM_SIZE - mark.height() + 1) / 2, mark.width(), mark.height(),
                        mark.sprite()));
                case Sign.Ware ware -> {
                    placed.add(new Placed.Item(x, 0, ware.stack()));
                    if (!ware.count().isEmpty()) {
                        placed.add(new Placed.Text(x + ITEM_SIZE - SUBSCRIPT_TUCK,
                            ITEM_SIZE - TEXT_INK + SUBSCRIPT_DROP, ware.count(), COUNT_COLOR));
                    }
                }
                case Sign.Word word ->
                    placed.add(new Placed.Text(x, (ITEM_SIZE - TEXT_INK) / 2, word.text(), word.color()));
            }
            x += width(font, sign);
        }
        if (shown < signs.size() && x + GAP + font.width(MORE) <= room) {
            placed.add(new Placed.Text(x + GAP, (ITEM_SIZE - TEXT_INK) / 2, MORE, WORD_COLOR));
            x += GAP + font.width(MORE);
        }
        return new Laid(x, List.copyOf(placed));
    }

    public static void draw(GuiGraphics graphics, Font font, Laid laid, int x, int y) {
        for (Placed placed : laid.placed()) {
            switch (placed) {
                case Placed.Glyph glyph ->
                    graphics.blitSprite(glyph.sprite(), x + glyph.x(), y + glyph.y(), glyph.width(), glyph.height());
                case Placed.Item item -> graphics.renderItem(item.stack(), x + item.x(), y + item.y());
                case Placed.Text text ->
                    graphics.drawString(font, text.text(), x + text.x(), y + text.y(), text.color(), true);
            }
        }
    }

    private static int width(Font font, List<Sign> signs, int count) {
        int width = 0;
        Sign before = null;
        for (Sign sign : signs.subList(0, count)) {
            width += gap(before, sign) + width(font, sign);
            before = sign;
        }
        return width;
    }

    private static int gap(Sign before, Sign sign) {
        if (before == null) {
            return 0;
        }
        return before instanceof Sign.Ware && sign instanceof Sign.Ware ? WARE_GAP : GAP;
    }

    private static int width(Font font, Sign sign) {
        return switch (sign) {
            case Sign.Mark mark -> mark.width();
            case Sign.Ware ware -> ITEM_SIZE + (ware.count().isEmpty() ? 0 : font.width(ware.count()) - SUBSCRIPT_TUCK);
            case Sign.Word word -> font.width(word.text());
        };
    }

    private static Sign word(Notice notice, int ink) {
        return new Sign.Word(notice.component().getString(), ink);
    }

    /** The sprite as drawn, at its own size, unless it is missing. */
    private static Optional<SpriteContents> drawn(ResourceLocation sprite) {
        SpriteContents contents = Minecraft.getInstance().getGuiSprites().getSprite(sprite).contents();
        return contents.name().equals(MissingTextureAtlasSprite.getLocation()) ? Optional.empty() : Optional.of(contents);
    }

    private static ResourceLocation ours(String path) {
        return ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, path);
    }
}
