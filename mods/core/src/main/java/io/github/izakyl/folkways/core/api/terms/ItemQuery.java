package io.github.izakyl.folkways.core.api.terms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

// What is typed into an item search, read the same way when it is searched and when it is kept as a rule. Words are
// all asked for at once and "|" offers another set of them; "@mod" is where an item comes from, "#tag" a tag it
// carries, and a bare word its id. A whole name means just that one: "@create" is Create and no other mod, "#logs"
// the logs tag, "minecraft:arrow" the arrow; part of one means any it is part of. "*" and "?" stand for any run of letters and any one letter.
// A search also finds a bare word in an item's name, which a rule cannot: names are the reader's language, and the
// server keeps none.
public final class ItemQuery {

    private static final int CACHED_QUERIES = 256;

    // Rules can be replaced indefinitely; keep only the results used most recently.
    private static final Map<String, List<Item>> FOUND = Collections.synchronizedMap(
        new LinkedHashMap<>(CACHED_QUERIES, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Item>> eldest) {
                return size() > CACHED_QUERIES;
            }
        });

    @Nullable
    private static volatile Known known;

    private final String text;
    private final List<List<Term>> alternatives;

    private ItemQuery(String text, List<List<Term>> alternatives) {
        this.text = text;
        this.alternatives = alternatives;
    }

    public static Optional<ItemQuery> parse(String typed) {
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
        return alternatives.isEmpty() ? Optional.empty() : Optional.of(new ItemQuery(text, List.copyOf(alternatives)));
    }

    // Tags and mods are read again once tags reload.
    public static void forget() {
        FOUND.clear();
        known = null;
    }

    public String text() {
        return text;
    }

    // The one item or tag this names, when it is a single whole id with nothing else asked.
    Optional<ItemFilter> lone() {
        if (alternatives.size() != 1 || alternatives.getFirst().size() != 1) {
            return Optional.empty();
        }
        Term only = alternatives.getFirst().getFirst();
        Known world = known();
        if (only instanceof Tag tag && tag.words().glob() == null && world.tags().contains(tag.words().text())) {
            return Optional.of(ItemFilter.tag(ResourceLocation.parse(tag.words().text())));
        }
        if (only instanceof Bare bare && bare.words().glob() == null && world.items().contains(bare.words().text())) {
            return Optional.of(ItemFilter.item(ResourceLocation.parse(bare.words().text())));
        }
        return Optional.empty();
    }

    // Whether a rule written this way takes in the item.
    public boolean matches(Item item) {
        return matches(item, null);
    }

    // Whether a search finds the item, its name in the reader's words among what a bare word is looked for in.
    public boolean matches(Item item, @Nullable String name) {
        Known world = known();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        for (List<Term> terms : alternatives) {
            boolean all = true;
            for (Term term : terms) {
                if (!term.test(world, item, id, name)) {
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

    // Every item the rule takes in, in the registry's order.
    public List<Item> items() {
        return FOUND.computeIfAbsent(text, any -> {
            List<Item> found = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item != Items.AIR && matches(item)) {
                    found.add(item);
                }
            }
            return List.copyOf(found);
        });
    }

    private static Known known() {
        Known held = known;
        if (held == null) {
            Set<String> mods = new HashSet<>();
            Set<String> items = new HashSet<>();
            Set<String> tags = new HashSet<>();
            Set<String> tagPaths = new HashSet<>();
            for (Item item : BuiltInRegistries.ITEM) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                mods.add(id.getNamespace());
                items.add(id.toString());
            }
            BuiltInRegistries.ITEM.getTagNames().forEach(tag -> {
                tags.add(tag.location().toString());
                tagPaths.add(tag.location().getPath());
            });
            held = new Known(Set.copyOf(mods), Set.copyOf(items), Set.copyOf(tags), Set.copyOf(tagPaths));
            known = held;
        }
        return held;
    }

    private record Known(Set<String> mods, Set<String> items, Set<String> tags, Set<String> tagPaths) {
    }

    private sealed interface Term {

        boolean test(Known world, Item item, ResourceLocation id, @Nullable String name);

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
        public boolean test(Known world, Item item, ResourceLocation id, @Nullable String name) {
            return words.find(id.getNamespace(), world.mods()::contains);
        }
    }

    private record Tag(Words words) implements Term {
        @Override
        public boolean test(Known world, Item item, ResourceLocation id, @Nullable String name) {
            boolean whole = words.text().contains(":");
            Predicate<String> named = whole ? world.tags()::contains : world.tagPaths()::contains;
            return item.builtInRegistryHolder().tags().map(TagKey::location)
                .anyMatch(tag -> words.find(whole ? tag.toString() : tag.getPath(), named));
        }
    }

    private record Bare(Words words) implements Term {
        @Override
        public boolean test(Known world, Item item, ResourceLocation id, @Nullable String name) {
            if (words.glob() != null) {
                return words.glob().matcher(words.text().contains(":") ? id.toString() : id.getPath()).matches();
            }
            // A whole id is that item alone; a bare word without a namespace may be part of a name.
            if (world.items().contains(words.text())) {
                return id.toString().equals(words.text());
            }
            return id.getPath().contains(words.text()) || words.text().contains(":") && id.toString()
                .contains(words.text()) || name != null && name.contains(words.text());
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
