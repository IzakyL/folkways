package io.github.izakyl.folkways.core.api.terms;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.izakyl.folkways.base.persist.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public record ItemSpec(Optional<ResourceLocation> item, Optional<TagKey<Item>> tag, Set<ItemSpec> anyOf,
                       Optional<ItemComponents> components) {

    public static final Codec<ItemSpec> CODEC = Codec.recursive("ItemSpec", self ->
        RecordCodecBuilder.create(instance -> instance.group(
        ResourceLocation.CODEC.optionalFieldOf("item").forGetter(ItemSpec::item),
        ResourceLocation.CODEC.xmap(id -> TagKey.<Item>create(Registries.ITEM, id), TagKey::location)
            .optionalFieldOf("tag").forGetter(ItemSpec::tag),
        self.listOf().xmap(Set::copyOf, ArrayList::new)
            .optionalFieldOf("anyOf", Set.of()).forGetter(ItemSpec::anyOf),
        ItemComponents.CODEC.optionalFieldOf("components").forGetter(ItemSpec::components)
    ).apply(instance, ItemSpec::new)));

    public ItemSpec(Optional<ResourceLocation> item, Optional<TagKey<Item>> tag, Set<ItemSpec> anyOf) {
        this(item, tag, anyOf, Optional.empty());
    }

    public ItemSpec {
        Objects.requireNonNull(item);
        Objects.requireNonNull(tag);
        Objects.requireNonNull(components);
        if (!anyOf.isEmpty() && components.isPresent()) {
            throw new IllegalArgumentException("component constraints belong on alternatives");
        }
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

    public static ItemSpec of(Item item) {
        return of(BuiltInRegistries.ITEM.getKey(item));
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

    /** Restricts these items by a component subset; custom_data uses NBT subset matching. */
    public ItemSpec withComponents(DataComponentMap values) {
        if (!anyOf.isEmpty()) {
            return anyOf(anyOf.stream().map(spec -> spec.withComponents(values)).toList());
        }
        return new ItemSpec(item, tag, anyOf, Optional.of(ItemComponents.subset(values)));
    }

    /** The stack's complete component identity, independent of its count. */
    public static ItemSpec exact(ItemStack stack) {
        if (stack.isEmpty()) throw new IllegalArgumentException("an empty stack has no goods identity");
        return new ItemSpec(Optional.of(BuiltInRegistries.ITEM.getKey(stack.getItem())), Optional.empty(), Set.of(),
            Optional.of(ItemComponents.exact(stack)));
    }

    public CompoundTag save() {
        return save(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }

    /** Supply world registries for components referring to enchantments or other datapack entries. */
    public CompoundTag save(HolderLookup.Provider registries) {
        return save(registries.createSerializationContext(NbtOps.INSTANCE));
    }

    private CompoundTag save(DynamicOps<Tag> ops) {
        return (CompoundTag) CODEC.encodeStart(ops, this).getOrThrow();
    }

    public static Optional<ItemSpec> load(Reader reader) {
        return load(reader, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }

    public static Optional<ItemSpec> load(Reader reader, HolderLookup.Provider registries) {
        return load(reader, registries.createSerializationContext(NbtOps.INSTANCE));
    }

    private static Optional<ItemSpec> load(Reader reader, DynamicOps<Tag> ops) {
        Optional<ItemComponents> components = reader.blob("components")
            .flatMap(saved -> ItemComponents.CODEC.parse(ops, saved).result());
        if (reader.keys().contains("components") && components.isEmpty()) return Optional.empty();
        // Old item/tag/anyOf records have no constraint and retain their original meaning.
        Optional<ResourceLocation> item = reader.id("item");
        if (item.isPresent()) {
            return Optional.of(new ItemSpec(item, Optional.empty(), Set.of(), components));
        }
        Optional<TagKey<Item>> tag = reader.id("tag").map(id -> TagKey.create(Registries.ITEM, id));
        if (tag.isPresent()) {
            return Optional.of(new ItemSpec(Optional.empty(), tag, Set.of(), components));
        }
        if (components.isPresent()) return Optional.empty();
        List<ItemSpec> alternatives = new ArrayList<>();
        for (Reader child : reader.children("anyOf")) {
            Optional<ItemSpec> alternative = load(child, ops);
            if (alternative.isEmpty()) return Optional.empty();
            alternatives.add(alternative.get());
        }
        return alternatives.isEmpty() ? Optional.empty() : Optional.of(anyOf(alternatives));
    }

    public boolean admits(ItemSpec alternative) {
        return anyOf.isEmpty() ? equals(alternative) : anyOf.contains(alternative);
    }

    public String describe() {
        if (!anyOf.isEmpty()) {
            return anyOf.stream().map(ItemSpec::describe).sorted().collect(Collectors.joining(" | "));
        }
        return item.map(ResourceLocation::toString).orElseGet(() -> "#" + tag.orElseThrow().location())
            + components.map(Object::toString).orElse("");
    }
}
