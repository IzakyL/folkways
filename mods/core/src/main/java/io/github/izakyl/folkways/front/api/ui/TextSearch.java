package io.github.izakyl.folkways.front.api.ui;

import java.util.Locale;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/** Case-insensitive searches in display text. Identifiers and saved rules keep their own matching. */
public final class TextSearch {

    @Nullable
    private static BiPredicate<String, String> extension;

    private TextSearch() {
    }

    /**
     * Installs additional matching during client setup. It receives lower-case text and query, only after a
     * literal match fails. Results are not cached: changes to the provider's spelling settings apply to the
     * next search, including predicates already prepared with {@link #query(String)}.
     */
    public static void install(BiPredicate<String, String> matcher) {
        extension = Objects.requireNonNull(matcher);
    }

    /** Prepares one query for a list of names without folding the query for each candidate. */
    public static Predicate<String> query(String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        return text -> folded(text.toLowerCase(Locale.ROOT), needle);
    }

    public static boolean contains(String text, String query) {
        return folded(text.toLowerCase(Locale.ROOT), query.toLowerCase(Locale.ROOT));
    }

    private static boolean folded(String text, String query) {
        if (text.contains(query)) {
            return true;
        }
        BiPredicate<String, String> matcher = extension;
        if (matcher != null) {
            // Pinyin cannot add a match to plain ASCII. Keep ids and English lists on the cheap path.
            for (int at = 0; at < text.length(); at++) {
                if (text.charAt(at) > 127) {
                    return matcher.test(text, query);
                }
            }
        }
        return false;
    }
}
