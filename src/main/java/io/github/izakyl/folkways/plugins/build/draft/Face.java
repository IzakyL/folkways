package io.github.izakyl.folkways.plugins.build.draft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * How a pattern reads to a player: what each of its choices offers and wears, and the words it brings for each
 * language. A pattern travels as its source, so its words travel with it rather than in a language file.
 */
public record Face(Map<String, List<Option>> offered, Map<String, Map<String, String>> words) {

    /** The language read when the player's own has no word for something. */
    public static final String FALLBACK = "en_us";

    /** The word a pattern is called by; a knob is worded under its key, an option under "knob.option". */
    public static final String TITLE = "title";

    public Face {
        Map<String, List<Option>> options = new LinkedHashMap<>();
        offered.forEach((knob, listed) -> options.put(knob, List.copyOf(listed)));
        offered = Map.copyOf(options);
        Map<String, Map<String, String>> spoken = new LinkedHashMap<>();
        words.forEach((language, said) -> spoken.put(language, Map.copyOf(said)));
        words = Map.copyOf(spoken);
    }

    public static Face plain() {
        return new Face(Map.of(), Map.of());
    }

    public List<Option> offered(String knob) {
        return offered.getOrDefault(knob, List.of());
    }

    public Optional<String> word(String language, String key) {
        String own = words.getOrDefault(language, Map.of()).get(key);
        if (own != null) {
            return Optional.of(own);
        }
        // A custom pattern may provide only one Chinese locale. Prefer another Chinese wording to English.
        List<String> relatives = switch (language) {
            case "zh_hk" -> List.of("zh_tw", "zh_cn");
            case "zh_tw" -> List.of("zh_hk", "zh_cn");
            case "zh_cn" -> List.of("zh_tw", "zh_hk");
            default -> List.of();
        };
        for (String relative : relatives) {
            String translated = words.getOrDefault(relative, Map.of()).get(key);
            if (translated != null) {
                return Optional.of(translated);
            }
        }
        return Optional.ofNullable(words.getOrDefault(FALLBACK, Map.of()).get(key));
    }

    public static String wordOf(String knob, Option option) {
        return knob + "." + option.key();
    }

    /** One thing a choice offers: its key as the pattern wrote it, and the item whose icon it wears. */
    public record Option(String key, Optional<ResourceLocation> icon) {
    }
}
