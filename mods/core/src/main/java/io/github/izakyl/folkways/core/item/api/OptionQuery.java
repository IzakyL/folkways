package io.github.izakyl.folkways.core.item.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

// The words a search asks for, independent of what is being searched. The caller supplies the names that exist,
// so a whole id, source or tag means just that one rather than everything containing its words.
public final class OptionQuery {

    public record Known(Set<String> mods, Set<String> items, Set<String> tags, Set<String> tagPaths) {
    }

    private final String text;
    private final List<List<Term>> alternatives;

    private OptionQuery(String text, List<List<Term>> alternatives) {
        this.text = text;
        this.alternatives = alternatives;
    }

    public static Optional<OptionQuery> parse(String typed) {
        String text = typed == null ? "" : String.join(" ", typed.trim().split("\\s+"));
        List<List<Term>> alternatives = new ArrayList<>();
        for (String part : text.split("\\|")) {
            List<Term> terms = new ArrayList<>();
            for (String word : part.trim().split("\\s+")) {
                Term term = Term.of(word.toLowerCase(Locale.ROOT));
                if (term != null) {
                    terms.add(term);
                }
            }
            if (!terms.isEmpty()) {
                alternatives.add(List.copyOf(terms));
            }
        }
        return alternatives.isEmpty() ? Optional.empty()
            : Optional.of(new OptionQuery(text, List.copyOf(alternatives)));
    }

    public String text() {
        return text;
    }

    public Optional<String> lone() {
        if (alternatives.size() != 1 || alternatives.getFirst().size() != 1) {
            return Optional.empty();
        }
        Term only = alternatives.getFirst().getFirst();
        if (only instanceof Tag tag && tag.words().glob() == null) {
            return Optional.of("#" + tag.words().text());
        }
        if (only instanceof Bare bare && bare.words().glob() == null) {
            return Optional.of(bare.words().text());
        }
        return Optional.empty();
    }

    public boolean matches(Known world, String id, String source, List<String> tags, @Nullable String name,
            BiPredicate<String, String> nameMatcher) {
        for (List<Term> terms : alternatives) {
            boolean all = true;
            for (Term term : terms) {
                if (!term.test(world, id, source, tags, name, nameMatcher)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static String path(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    private sealed interface Term {

        boolean test(Known world, String id, String source, List<String> tags, @Nullable String name,
            BiPredicate<String, String> nameMatcher);

        @Nullable
        static Term of(String word) {
            if (word.isEmpty()) {
                return null;
            }
            char sign = word.charAt(0);
            String rest = word.substring(1);
            if ((sign == '@' || sign == '#') && rest.isEmpty()) {
                return null;
            }
            return switch (sign) {
                case '@' -> new Mod(Words.of(rest));
                case '#' -> new Tag(Words.of(rest));
                default -> new Bare(Words.of(word));
            };
        }
    }

    private record Mod(Words words) implements Term {
        @Override
        public boolean test(Known world, String id, String source, List<String> tags, @Nullable String name,
                BiPredicate<String, String> nameMatcher) {
            return words.find(source, world.mods()::contains);
        }
    }

    private record Tag(Words words) implements Term {
        @Override
        public boolean test(Known world, String id, String source, List<String> tags, @Nullable String name,
                BiPredicate<String, String> nameMatcher) {
            boolean whole = words.text().contains(":");
            Predicate<String> named = whole ? world.tags()::contains : world.tagPaths()::contains;
            return tags.stream().anyMatch(tag -> words.find(whole ? tag : path(tag), named));
        }
    }

    private record Bare(Words words) implements Term {
        @Override
        public boolean test(Known world, String id, String source, List<String> tags, @Nullable String name,
                BiPredicate<String, String> nameMatcher) {
            if (words.glob() != null) {
                return words.glob().matcher(words.text().contains(":") ? id : path(id)).matches();
            }
            // A whole id is that item alone; a bare word without a namespace may be part of a name.
            if (world.items().contains(words.text())) {
                return id.equals(words.text());
            }
            return path(id).contains(words.text()) || words.text().contains(":") && id
                .contains(words.text()) || name != null && nameMatcher.test(name, words.text());
        }
    }

    // A word looked for in a name: matched whole when it is one of the names there are, or as a pattern when it
    // has stars in it, and found anywhere in the name otherwise.
    private record Words(String text, @Nullable Pattern glob) {

        static Words of(String text) {
            return new Words(text, text.indexOf('*') >= 0 || text.indexOf('?') >= 0 ? globOf(text) : null);
        }

        boolean find(String in, Predicate<String> isName) {
            if (glob != null) {
                return glob.matcher(in).matches();
            }
            return isName.test(text) ? in.equals(text) : in.contains(text);
        }

        private static Pattern globOf(String text) {
            StringBuilder regex = new StringBuilder();
            StringBuilder plain = new StringBuilder();
            for (char letter : text.toCharArray()) {
                if (letter == '*' || letter == '?') {
                    if (!plain.isEmpty()) {
                        regex.append(Pattern.quote(plain.toString()));
                        plain.setLength(0);
                    }
                    regex.append(letter == '*' ? ".*" : ".");
                } else {
                    plain.append(letter);
                }
            }
            if (!plain.isEmpty()) {
                regex.append(Pattern.quote(plain.toString()));
            }
            return Pattern.compile(regex.toString());
        }
    }
}
