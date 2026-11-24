package io.github.izakyl.folkways.core.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.LDLibFonts;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.card.api.notice.Notice;
import io.github.izakyl.folkways.core.card.api.notice.Sentence;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A sentence as it is drawn: each token made a sign, a glyph whose sprite is missing made its words, and the signs
 * laid in a line {@link #HEIGHT} tall, each centred on the items' height, an item's count set small in the item's
 * bottom-right corner, as a slot prints a stack's count but at about half the size, so the item shows past it.
 */
public final class Signs {
    public static final int ITEM_SIZE = 16;
    public static final int HEIGHT = ITEM_SIZE + 1;
    public static final int WORD_COLOR = 0xFFAAAAAA;
    public static final int COUNT_COLOR = 0xFFFFFFFF;
    // How far back an item sits before LDLib2's item drawer lifts it, as LDLib2's own item icons are.
    private static final float ITEM_DEPTH = -200.0F;
    private static final int TEXT_INK = 8;
    private static final int GAP = 2;
    // Items side by side, with no plus between them, stand a little apart so each reads as its own.
    private static final int WARE_GAP = 3;
    // A count's digits stand seven of the font's rows tall, its shadow one more, as dark as the font's own shadow.
    private static final int COUNT_INK = 7;
    private static final int SHADOW = 0xFF3F3F3F;
    private static final String MORE = "…";
    // Glyphs are drawn white with a dark rim for the world's dark cards. On the ledger's paper each is drawn from its
    // twin under ledger/, the white inked and the rim dropped; one with no twin is tinted to ink whole, and those whose
    // own colour is what they say are left as they are. Vocation and group sprites keep their colour in every
    // namespace.
    private static final String LEDGER = "ledger/";
    private static final Set<ResourceLocation> COLOURED = Set.of(ours("glyph/admits"), ours("glyph/refuses"),
        ours("glyph/lacks"), ours("glyph/mood_happy"), ours("glyph/mood_neutral"), ours("glyph/mood_sad"));
    private static final float INK_RED = (Rows.INK >> 16 & 0xFF) / 255f;
    private static final float INK_GREEN = (Rows.INK >> 8 & 0xFF) / 255f;
    private static final float INK_BLUE = (Rows.INK & 0xFF) / 255f;
    private static final Sign PLUS = new Sign.Mark(ours("glyph/plus"), 6, 6);
    private static final Sign YIELDS = new Sign.Mark(ours("glyph/yields"), 8, 6);

    private Signs() {
    }

    /** One picture in a line of them: a glyph, an item with its count over its corner, or a word. */
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

        /** An item's count, at the item's top-left x,y, drawn small over its bottom-right corner with a shadow. */
        record Count(int x, int y, String text, int color) implements Placed {
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
                    ItemStack stack = ware.stack(Minecraft.getInstance().level.registryAccess());
                    if (!stack.isEmpty()) {
                        signs.add(new Sign.Ware(stack, ware.count() > 1L ? compact(ware.count()) : ""));
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
        if (count < 1_000_000L) {
            return count / 1_000L + "k";
        }
        if (count < 10_000_000L) {
            return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0D);
        }
        return count / 1_000_000L + "M";
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
                        placed.add(new Placed.Count(x, 0, ware.count(), COUNT_COLOR));
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
                case Placed.Glyph glyph -> {
                    ResourceLocation twin = glyph.sprite().withPrefix(LEDGER);
                    boolean coloured = coloured(glyph.sprite());
                    boolean tinted = !coloured && drawn(twin).isEmpty();
                    if (tinted) {
                        graphics.setColor(INK_RED, INK_GREEN, INK_BLUE, 1f);
                    }
                    graphics.blitSprite(tinted || coloured ? glyph.sprite() : twin,
                        x + glyph.x(), y + glyph.y(), glyph.width(), glyph.height());
                    if (tinted) {
                        graphics.setColor(1f, 1f, 1f, 1f);
                    }
                }
                case Placed.Item item -> drawItem(graphics, item.stack(), x + item.x(), y + item.y());
                // Words are inked onto the page; only a count, white over its item, keeps the shadow it needs.
                case Placed.Text text -> LDLibFonts.drawText(graphics, font, Component.literal(text.text()),
                    x + text.x(), y + text.y(), text.color(), false);
                // The item's drawer cleared the depth behind it, so the count needs no lift to show.
                case Placed.Count count -> drawCount(graphics, font, count.text(), x + count.x(), y + count.y(),
                    count.color(), 0.0F);
            }
        }
    }

    private static boolean coloured(ResourceLocation sprite) {
        return vocation(sprite) || COLOURED.contains(sprite);
    }

    private static boolean vocation(ResourceLocation sprite) {
        return sprite.getPath().startsWith("vocation/") || sprite.getPath().startsWith("vocation_group/");
    }

    /** A coloured vocation's light-rimmed twin for world cards and HUDs, in the sprite's own namespace. */
    public static ResourceLocation onDark(ResourceLocation sprite) {
        if (vocation(sprite)) {
            ResourceLocation twin = sprite.withPrefix("dark/");
            if (drawn(twin).isPresent()) {
                return twin;
            }
        }
        return sprite;
    }

    // An item drawn as LDLib2's own item icons are: pushed back, lifted by its drawer, and the depth it left cleared,
    // so it is never culled behind what lies under the page nor stands in front of a dialog drawn over it.
    private static void drawItem(GuiGraphics graphics, ItemStack stack, int x, int y) {
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, ITEM_DEPTH);
        DrawerHelper.drawItemStack(graphics, stack, x, y, -1, null);
        graphics.pose().popPose();
    }

    /**
     * Draws {@code count} small in the bottom-right corner of the item whose top-left is at x,y, white digits over a
     * shadow as a slot prints them, lifted {@code lift} toward the viewer to stand in front of the item. Where the
     * screen is fine enough the font's own digits are drawn at about half size, each of their pixels a whole number of
     * the screen's; where it is not (GUI scale 1), the font cannot shrink and stay legible, so the digits are drawn
     * from a three-by-five figure set instead, a screen pixel to each of theirs.
     */
    public static void drawCount(GuiGraphics graphics, Font font, String count, float x, float y, int color,
                                 float lift) {
        double pixels = Minecraft.getInstance().getWindow().getGuiScale()
            * Math.abs(graphics.pose().last().pose().m00());
        graphics.pose().pushPose();
        if (pixels >= 2.0D) {
            float scale = (float) (Math.round(pixels / 2.0D) / pixels);
            graphics.pose().translate(x + countLeft(font, count, scale), y + countTop(scale), lift);
            graphics.pose().scale(scale, scale, 1.0F);
            LDLibFonts.drawText(graphics, font, Component.literal(count), 0, 0, color, true);
        } else {
            float dot = pixels > 0.0D ? (float) (1.0D / pixels) : 1.0F;
            int wide = Figures.width(count);
            // Ink up to the item's right and bottom edges, the shadow a dot past them, set on whole screen pixels so
            // every dot fills exactly one.
            var matrix = graphics.pose().last().pose();
            double gui = Minecraft.getInstance().getWindow().getGuiScale();
            float left = Math.max(x, x + ITEM_SIZE - wide * dot);
            float top = y + ITEM_SIZE - Figures.HEIGHT * dot;
            if (matrix.m00() != 0.0F && matrix.m11() != 0.0F) {
                left = (float) ((Math.round((matrix.m00() * left + matrix.m30()) * gui) / gui - matrix.m30())
                    / matrix.m00());
                top = (float) ((Math.round((matrix.m11() * top + matrix.m31()) * gui) / gui - matrix.m31())
                    / matrix.m11());
            }
            graphics.pose().translate(left, top, lift);
            graphics.pose().scale(dot, dot, 1.0F);
            Figures.draw(graphics, count, 1, 1, SHADOW);
            Figures.draw(graphics, count, 0, 0, color);
        }
        graphics.pose().popPose();
    }

    /** Small figures three dots wide and five tall, for counts on screens too coarse to shrink the font's. */
    private static final class Figures {
        static final int HEIGHT = 5;
        private static final Map<Character, String[]> SHAPES = Map.ofEntries(
            Map.entry('0', new String[] {"###", "#.#", "#.#", "#.#", "###"}),
            Map.entry('1', new String[] {".#.", "##.", ".#.", ".#.", "###"}),
            Map.entry('2', new String[] {"###", "..#", "###", "#..", "###"}),
            Map.entry('3', new String[] {"###", "..#", "###", "..#", "###"}),
            Map.entry('4', new String[] {"#.#", "#.#", "###", "..#", "..#"}),
            Map.entry('5', new String[] {"###", "#..", "###", "..#", "###"}),
            Map.entry('6', new String[] {"###", "#..", "###", "#.#", "###"}),
            Map.entry('7', new String[] {"###", "..#", "..#", ".#.", ".#."}),
            Map.entry('8', new String[] {"###", "#.#", "###", "#.#", "###"}),
            Map.entry('9', new String[] {"###", "#.#", "###", "..#", "###"}),
            Map.entry('.', new String[] {".", ".", ".", ".", "#"}),
            Map.entry('k', new String[] {"#..", "#.#", "##.", "#.#", "#.#"}),
            Map.entry('M', new String[] {"#...#", "##.##", "#.#.#", "#...#", "#...#"}));

        private Figures() {
        }

        static int width(String text) {
            int width = 0;
            for (char each : text.toCharArray()) {
                String[] shape = SHAPES.get(each);
                width += shape == null ? 0 : shape[0].length() + 1;
            }
            return Math.max(0, width - 1);
        }

        static void draw(GuiGraphics graphics, String text, int x, int y, int color) {
            int at = x;
            for (char each : text.toCharArray()) {
                String[] shape = SHAPES.get(each);
                if (shape == null) {
                    continue;
                }
                for (int row = 0; row < HEIGHT; row++) {
                    for (int column = 0; column < shape[row].length(); column++) {
                        if (shape[row].charAt(column) == '#') {
                            graphics.fill(at + column, y + row, at + column + 1, y + row + 1, color);
                        }
                    }
                }
                at += shape[0].length() + 1;
            }
        }
    }

    /**
     * Where a count drawn at {@code scale} starts, from its item's left: its digits' right edge on the item's, its
     * shadow just past it, as in a slot; a count too wide for the item starts at the item's left edge instead.
     */
    public static float countLeft(Font font, String count, float scale) {
        return Math.max(0.0F, ITEM_SIZE + scale - font.width(count) * scale);
    }

    /** Where a count drawn at {@code scale} starts, from its item's top: its digits' foot on the item's. */
    public static float countTop(float scale) {
        return ITEM_SIZE - COUNT_INK * scale;
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
            case Sign.Ware ware -> ITEM_SIZE;
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
        return ResourceLocation.fromNamespaceAndPath(FolkwaysCoreMod.MOD_ID, path);
    }
}
