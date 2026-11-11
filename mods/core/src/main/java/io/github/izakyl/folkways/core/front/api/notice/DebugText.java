package io.github.izakyl.folkways.core.front.api.notice;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;

/**
 * Words for the focused debug card. Translations stay plain; colour rides on the argument components, so the
 * unstyled template text (the labels) is drawn in the card's dim label colour while values stand out.
 */
public final class DebugText {
    public static final String PREFIX = "folkways.debug.overlay.";
    private static final String SECTION = PREFIX + "section.";

    /** Unstyled label text; the client draws it in this colour. */
    public static final int LABEL = 0x8C99A6;
    /** Numbers, quantities and other plain values. */
    public static final int VALUE = 0xE6EDF3;
    /** The work a resident does: "Tilling soil". */
    public static final int WORK = 0xFFFFFF;
    /** Goods and item names. */
    public static final int ITEM = 0xF2D58A;
    /** Residents and other entities. */
    public static final int NAME = 0xD9A6FF;
    /** Coordinates and identifiers that are looked up rather than read. */
    public static final int WHERE = 0x66737F;
    public static final int GOOD = 0x6EDC7A;
    public static final int WAIT = 0xFFC24D;
    public static final int BAD = 0xFF6B6B;
    public static final int MUTED = 0x6E7882;
    public static final int SECTION_COLOR = 0x6FD3F2;
    /** Goods kept by the resident for itself: its kit, rations and keeps. */
    public static final int OWN = 0x5DADE2;
    /** Goods the plan has given to work: cargo to deliver or spend, stock a lot claim will draw. */
    public static final int PLANNED = 0xFF9F43;
    /** Goods nobody has a use for yet: cargo waiting to be put away, a store's free stock. */
    public static final int LOOSE = 0xC8CFD6;
    /** Room the plan has set aside in a store for goods still on their way. */
    public static final int SPACE = 0x48D1CC;

    private static final Set<String> GOOD_WORDS = Set.of("working", "ready", "observed", "matches", "arrived",
        "holding", "walking", "true");
    private static final Set<String> WAIT_WORDS = Set.of("pending", "queued", "dirty", "waiting", "approaching",
        "settling", "committing", "beats", "winding_up", "stalled", "urge", "unobserved", "unloaded", "moving",
        "way_pending", "not_ready", "worker_away", "beyond_horizon", "controller", "directed", "engaged",
        "vitals_unobserved", "inventory_unobserved");
    private static final Set<String> BAD_WORDS = Set.of("failed", "declined", "differs", "unsupported",
        "unavailable", "no_free_hand", "no_licence", "no_tool", "no_way", "pack_full", "pack_busy", "claim_taken",
        "same_worker_split", "no_free_stand", "pack_order", "death", "revoked", "no_bed", "bed_elsewhere",
        "no_meal", "off_graph");
    private static final Set<String> MUTED_WORDS = Set.of("done", "cancelled", "idle", "none", "nobody", "false",
        "released", "replaced", "other", "terminal", "not_hungry", "not_tired");

    /** Section headers and the sprite drawn before each. */
    private static final Map<String, String> ICONS = Map.ofEntries(
        Map.entry("standing", "standing"), Map.entry("current", "current"), Map.entry("route", "route"),
        Map.entry("urges", "urges"), Map.entry("carrying", "carrying"), Map.entry("events", "events"),
        Map.entry("combat", "combat"), Map.entry("store", "store"), Map.entry("reservations", "reservations"),
        Map.entry("work", "work"), Map.entry("extension", "extension"));

    private DebugText() {
    }

    /** {@code folkways.debug.overlay.<key>} with numbers, booleans and strings styled as values. */
    public static MutableComponent text(String key, Object... args) {
        return Component.translatable(PREFIX + key, Arrays.stream(args).map(DebugText::arg).toArray());
    }

    public static Line line(String key, Object... args) {
        return Line.text(text(key, args));
    }

    /** A header: bold, coloured, and recognised by the client for its icon. */
    public static Line section(String key, Object... args) {
        return Line.text(text("section." + key, args).withStyle(ChatFormatting.BOLD)
            .withColor(SECTION_COLOR));
    }

    /** The sprite for a section header line, if the line is one. */
    public static java.util.Optional<ResourceLocation> icon(Component line) {
        if (line.getContents() instanceof TranslatableContents translated
                && translated.getKey().startsWith(SECTION)) {
            String name = ICONS.get(translated.getKey().substring(SECTION.length()));
            if (name != null) {
                return java.util.Optional.of(ResourceLocation.fromNamespaceAndPath("folkways_core", "debug/" + name));
            }
        }
        return java.util.Optional.empty();
    }

    /** A {@code value.<key>} word coloured by what the state means. */
    public static MutableComponent word(String key) {
        String name = key.toLowerCase(Locale.ROOT);
        return Component.translatable(PREFIX + "value." + name).withColor(tone(name));
    }

    public static MutableComponent yes(boolean value) {
        return word(Boolean.toString(value));
    }

    public static MutableComponent value(Object value) {
        return Component.literal(String.valueOf(value)).withColor(VALUE);
    }

    public static MutableComponent where(String at) {
        return Component.literal(at).withColor(WHERE);
    }

    public static MutableComponent work(Component work) {
        return work.copy().withColor(WORK);
    }

    public static MutableComponent item(Component item) {
        return item.copy().withColor(ITEM);
    }

    public static MutableComponent name(Component name) {
        return name.copy().withColor(NAME);
    }

    public static MutableComponent tone(Component text, int color) {
        return text.copy().withColor(color);
    }

    /** Parts joined by a dim " · ". */
    public static MutableComponent join(List<? extends Component> parts) {
        MutableComponent result = Component.empty();
        boolean first = true;
        for (Component part : parts) {
            if (!first) {
                result.append(" · ");
            }
            result.append(part);
            first = false;
        }
        return result;
    }

    private static Object arg(Object arg) {
        return switch (arg) {
            case Boolean value -> yes(value);
            case Number number -> value(number);
            case String string -> value(string);
            default -> arg;
        };
    }

    private static int tone(String word) {
        if (GOOD_WORDS.contains(word)) {
            return GOOD;
        }
        if (WAIT_WORDS.contains(word)) {
            return WAIT;
        }
        if (BAD_WORDS.contains(word)) {
            return BAD;
        }
        if (MUTED_WORDS.contains(word)) {
            return MUTED;
        }
        return VALUE;
    }
}
