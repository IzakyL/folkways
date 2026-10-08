package io.github.izakyl.folkways.core.api.terms;

import io.github.izakyl.folkways.core.api.persist.Entries;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public record ItemSpec(Optional<ResourceLocation> item, Optional<TagKey<Item>> tag, Set<ItemSpec> anyOf) {

    private static final String TAG_ITEM = "item";
    private static final String TAG_TAG = "tag";
    private static final String TAG_ANY_OF = "anyOf";

    public ItemSpec {
        Objects.requireNonNull(item);
        Objects.requireNonNull(tag);
        anyOf = Set.copyOf(Objects.requireNonNull(anyOf));
        int forms = (item.isPresent() ? 1 : 0) + (tag.isPresent() ? 1 : 0) + (anyOf.isEmpty() ? 0 : 1);
        if (forms != 1) {
            throw new IllegalArgumentException(
                "ItemSpec must be exactly one of an item, a tag, or a set of alternatives");
        }
    }

    public static ItemSpec of(ResourceLocation id) {
        return new ItemSpec(Optional.of(Objects.requireNonNull(id)), Optional.empty(), Set.of());
    }

    public static ItemSpec of(TagKey<Item> tag) {
        return new ItemSpec(Optional.empty(), Optional.of(Objects.requireNonNull(tag)), Set.of());
    }

    public static ItemSpec anyOf(Collection<ItemSpec> alternatives) {
        Set<ItemSpec> flat = Objects.requireNonNull(alternatives).stream()
            .flatMap(alt -> alt.anyOf().isEmpty() ? Stream.of(alt) : alt.anyOf().stream())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (flat.isEmpty()) {
            throw new IllegalArgumentException("anyOf needs at least one alternative");
        }
        return flat.size() == 1
            ? flat.iterator().next()
            : new ItemSpec(Optional.empty(), Optional.empty(), flat);
    }

    public CompoundTag save() {
        Writer writer = Writer.of()
            .id(TAG_ITEM, item)
            .id(TAG_TAG, tag.map(TagKey::location));
        if (!anyOf.isEmpty()) {
            writer.children(TAG_ANY_OF, anyOf, ItemSpec::save);
        }
        return writer.tag();
    }

    public static Optional<ItemSpec> load(Reader reader) {
        Optional<ResourceLocation> item = reader.id(TAG_ITEM);
        if (item.isPresent()) {
            return item.map(ItemSpec::of);
        }
        Optional<ResourceLocation> tag = reader.id(TAG_TAG);
        if (tag.isPresent()) {
            return tag.map(id -> ItemSpec.of(TagKey.<Item>create(Registries.ITEM, id)));
        }
        List<ItemSpec> alternatives = Entries.of(reader, TAG_ANY_OF, ItemSpec::load);
        return alternatives.isEmpty() ? Optional.empty() : Optional.of(ItemSpec.anyOf(alternatives));
    }

    public boolean admits(ItemSpec alternative) {
        return anyOf.isEmpty() ? equals(alternative) : anyOf.contains(alternative);
    }

    public String describe() {
        if (!anyOf.isEmpty()) {
            return anyOf.stream().map(ItemSpec::describe).sorted().collect(Collectors.joining(" | "));
        }
        return item.map(ResourceLocation::toString).orElseGet(() -> "#" + tag.orElseThrow().location());
    }
}
