package io.github.izakyl.folkways.core.api.terms;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

// One rule of an item list: an item, a tag, or a query as it was typed into a search, which stands for every item it
// finds there.
public record ItemFilter(Kind kind, String text) {

    public enum Kind {
        ITEM,
        TAG,
        QUERY
    }

    public ItemFilter {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        if (kind != Kind.QUERY && ResourceLocation.tryParse(text) == null) {
            throw new IllegalArgumentException("not an id: " + text);
        }
    }

    public static ItemFilter item(ResourceLocation id) {
        return new ItemFilter(Kind.ITEM, id.toString());
    }

    public static ItemFilter tag(ResourceLocation id) {
        return new ItemFilter(Kind.TAG, id.toString());
    }

    // Every rule is a query; one naming a single item or a single tag, whole and without stars, is kept as that
    // item or tag, which is read the same and costs nothing to expand.
    public static Optional<ItemFilter> query(String text) {
        return ItemQuery.parse(text).map(query -> query.lone()
            .orElseGet(() -> new ItemFilter(Kind.QUERY, query.text())));
    }

    public boolean tag() {
        return kind == Kind.TAG;
    }

    public boolean query() {
        return kind == Kind.QUERY;
    }

    // The item or tag it names; a query names neither.
    public ResourceLocation id() {
        if (kind == Kind.QUERY) {
            throw new IllegalStateException("a query names no one id: " + text);
        }
        return ResourceLocation.parse(text);
    }

    public String describe() {
        return switch (kind) {
            case ITEM, QUERY -> text;
            case TAG -> "#" + text;
        };
    }

    // As one line, the kind first, so a list of them travels as lines of text.
    public String write() {
        return switch (kind) {
            case ITEM -> "i" + text;
            case TAG -> "t" + text;
            case QUERY -> "q" + text;
        };
    }

    public static Optional<ItemFilter> read(String line) {
        if (line.length() < 2) {
            return Optional.empty();
        }
        String text = line.substring(1);
        return switch (line.charAt(0)) {
            case 'i' -> Optional.ofNullable(ResourceLocation.tryParse(text)).map(ItemFilter::item);
            case 't' -> Optional.ofNullable(ResourceLocation.tryParse(text)).map(ItemFilter::tag);
            case 'q' -> query(text);
            default -> Optional.empty();
        };
    }
}
