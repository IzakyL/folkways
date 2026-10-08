package io.github.izakyl.folkways.front.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The Workforce search line. A bare word is looked for in a resident's name, current work and the
 * trades he is allowed; {@code field=value} narrows it to one field. Words must all hold, {@code |}
 * offers alternatives, a leading {@code -} or {@code !} negates, and quotes keep spaces in a value.
 *
 * <pre>
 *   name=ann            name contains "ann"
 *   doing:wheat         current work contains "wheat"
 *   farming=1           farming allowed at priority 1
 *   farming&lt;=2         farming allowed at priority 1 or 2
 *   farming=off         farming forbidden (or not a trade he knows)
 *   farming=on          farming allowed at any priority
 *   -name=bob | mining=1
 * </pre>
 */
public final class RosterQuery {

    /** The fields a query can name, each under its own words and the words shown in the header. */
    public record Fields(List<String> name, List<String> doing) {
    }

    /** A trade as the query sees it: every word it answers to, and the resident's standing in it. */
    public record Trade(List<String> words, int standing) {
    }

    public record Entry(String name, String doing, List<Trade> trades) {
    }

    private static final List<String> ON = List.of("on", "yes", "y", "true", "allowed", "allow");
    private static final List<String> OFF = List.of("off", "no", "n", "false", "forbidden", "forbid", "-", "x");
    private static final List<String> OPERATORS = List.of("!=", ">=", "<=", "=", ":", ">", "<");

    private RosterQuery() {
    }

    public static Predicate<Entry> parse(String query, Fields fields) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return entry -> true;
        }
        Predicate<Entry> any = entry -> false;
        for (List<String> group : groups(trimmed)) {
            Predicate<Entry> all = entry -> true;
            for (String word : group) {
                all = all.and(term(word, fields));
            }
            any = any.or(all);
        }
        return any;
    }

    // Splits on blanks and on |, keeping quoted runs whole and dropping the quotes.
    private static List<List<String>> groups(String query) {
        List<List<String>> groups = new ArrayList<>();
        List<String> group = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean quoted = false;
        for (int at = 0; at < query.length(); at++) {
            char c = query.charAt(at);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (Character.isWhitespace(c) || c == '|')) {
                flush(word, group);
                if (c == '|') {
                    groups.add(group);
                    group = new ArrayList<>();
                }
            } else {
                word.append(c);
            }
        }
        flush(word, group);
        groups.add(group);
        groups.removeIf(List::isEmpty);
        return groups;
    }

    private static void flush(StringBuilder word, List<String> group) {
        if (!word.isEmpty()) {
            group.add(word.toString().toLowerCase(Locale.ROOT));
            word.setLength(0);
        }
    }

    private static Predicate<Entry> term(String word, Fields fields) {
        if (word.length() > 1 && (word.charAt(0) == '-' || word.charAt(0) == '!')) {
            return term(word.substring(1), fields).negate();
        }
        return field(word, fields).orElseGet(() -> anywhere(word));
    }

    private static Predicate<Entry> anywhere(String needle) {
        return entry -> has(entry.name(), needle) || has(entry.doing(), needle)
            || entry.trades().stream().anyMatch(trade -> trade.standing() > 0
                && trade.words().stream().anyMatch(said -> has(said, needle)));
    }

    // A word that does not name a known field is no field at all, so "a:b" in a name is still found.
    private static Optional<Predicate<Entry>> field(String word, Fields fields) {
        for (int at = 1; at < word.length(); at++) {
            for (String operator : OPERATORS) {
                if (word.startsWith(operator, at)) {
                    String key = word.substring(0, at);
                    String value = word.substring(at + operator.length());
                    return keyed(key, operator, value, fields);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Predicate<Entry>> keyed(String key, String operator, String value, Fields fields) {
        if (named(fields.name(), key)) {
            return Optional.of(text(operator, value, Entry::name));
        }
        if (named(fields.doing(), key)) {
            return Optional.of(text(operator, value, Entry::doing));
        }
        Optional<Predicate<Integer>> standing = standing(operator, value);
        if (standing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(entry -> {
            List<Trade> matching = entry.trades().stream().filter(trade -> named(trade.words(), key)).toList();
            return !matching.isEmpty() && matching.stream().anyMatch(trade -> standing.get().test(trade.standing()));
        });
    }

    private static Predicate<Entry> text(String operator, String value,
            Function<Entry, String> field) {
        Predicate<Entry> contains = entry -> has(field.apply(entry), value);
        return operator.equals("!=") ? contains.negate() : contains;
    }

    // A standing is the priority 1 to 4 while allowed, its negative while forbidden, and 0 for no such trade.
    private static Optional<Predicate<Integer>> standing(String operator, String value) {
        boolean not = operator.equals("!=");
        if (ON.contains(value) && (not || operator.equals("=") || operator.equals(":"))) {
            return Optional.of(standing -> standing > 0 != not);
        }
        if (OFF.contains(value) && (not || operator.equals("=") || operator.equals(":"))) {
            return Optional.of(standing -> standing <= 0 != not);
        }
        int wanted;
        try {
            wanted = Integer.parseInt(value);
        } catch (NumberFormatException notNumber) {
            return Optional.empty();
        }
        Predicate<Integer> compared = switch (operator) {
            case "=", ":", "!=" -> standing -> standing == wanted;
            case ">" -> standing -> standing > wanted;
            case "<" -> standing -> standing < wanted;
            case ">=" -> standing -> standing >= wanted;
            case "<=" -> standing -> standing <= wanted;
            default -> standing -> false;
        };
        Predicate<Integer> allowed = standing -> standing > 0 && compared.test(standing);
        return Optional.of(not ? allowed.negate() : allowed);
    }

    private static boolean named(List<String> words, String key) {
        return words.stream().anyMatch(said -> said.toLowerCase(Locale.ROOT).equals(key));
    }

    private static boolean has(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).contains(needle);
    }
}
