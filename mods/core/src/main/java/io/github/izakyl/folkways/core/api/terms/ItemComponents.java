package io.github.izakyl.folkways.core.api.terms;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

// Component predicates may use recursive custom_data matching; exact compares the whole map.
public final class ItemComponents {
    private static final Codec<DataComponentMap> PERSISTENT = DataComponentMap.CODEC.validate(components ->
        components.keySet().stream().anyMatch(DataComponentType::isTransient)
            ? DataResult.error(() -> "Item constraints cannot persist transient components")
            : DataResult.success(components));

    /** Encoding refuses transient values rather than dropping part of a constraint. */
    public static final Codec<ItemComponents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        PERSISTENT.fieldOf("values").forGetter(ItemComponents::values),
        Codec.BOOL.optionalFieldOf("exact", false).forGetter(ItemComponents::exact),
        Codec.BOOL.optionalFieldOf("customDataSubset", true).forGetter(components -> components.customDataSubset)
    ).apply(instance, ItemComponents::new));

    private final Map<DataComponentType<?>, Object> values;
    private final boolean exact;
    private final boolean customDataSubset;

    private ItemComponents(DataComponentMap components, boolean exact, boolean customDataSubset) {
        Map<DataComponentType<?>, Object> values = new LinkedHashMap<>();
        for (TypedDataComponent<?> component : components) {
            values.put(component.type(), component.value());
        }
        this.values = Map.copyOf(values);
        this.exact = exact;
        this.customDataSubset = exact || customDataSubset;
    }

    public static ItemComponents subset(DataComponentMap components) {
        return new ItemComponents(components, false, true);
    }

    static ItemComponents matching(DataComponentPredicate predicate) {
        return new ItemComponents(predicate.asPatch().split().added(), false, false);
    }

    public static ItemComponents exact(ItemStack stack) {
        return new ItemComponents(stack.getComponents(), true, true);
    }

    public boolean exact() {
        return exact;
    }

    private DataComponentMap values() {
        DataComponentMap.Builder builder = DataComponentMap.builder();
        values.forEach((type, value) -> set(builder, type, value));
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static <T> void set(DataComponentMap.Builder builder, DataComponentType<T> type, Object value) {
        builder.set(type, (T) value);
    }

    public boolean matches(ItemStack stack) {
        return matches(stack.getComponents());
    }

    private boolean matches(DataComponentMap components) {
        if (exact && values.size() != components.size()) return false;
        for (var entry : values.entrySet()) {
            Object actual = components.get(entry.getKey());
            if (!exact && customDataSubset && entry.getKey() == DataComponents.CUSTOM_DATA) {
                if (!(actual instanceof CustomData data)
                        || !data.matchedBy(((CustomData) entry.getValue()).copyTag())) return false;
            } else if (!Objects.equals(entry.getValue(), actual)) {
                return false;
            }
        }
        return true;
    }

    void apply(ItemStack stack) {
        if (exact) {
            for (DataComponentType<?> type : Set.copyOf(stack.getComponents().keySet())) {
                if (!values.containsKey(type)) stack.remove(type);
            }
        }
        stack.applyComponents(values());
    }

    boolean overlaps(ItemComponents other) {
        if (exact) return other.matches(values());
        if (other.exact) return matches(other.values());
        for (var entry : values.entrySet()) {
            Object second = other.values.get(entry.getKey());
            if (second == null) continue;
            if (entry.getKey() == DataComponents.CUSTOM_DATA && (customDataSubset || other.customDataSubset)) {
                CompoundTag first = ((CustomData) entry.getValue()).copyTag();
                CompoundTag next = ((CustomData) second).copyTag();
                if (customDataSubset && other.customDataSubset ? !compatible(first, next)
                    : !NbtUtils.compareNbt(customDataSubset ? first : next, customDataSubset ? next : first, true)) return false;
            } else if (!entry.getValue().equals(second)) {
                return false;
            }
        }
        return true;
    }

    boolean includes(ItemComponents offered) {
        if (offered.exact) return matches(offered.values());
        if (exact) return false;
        for (var entry : values.entrySet()) {
            Object value = offered.values.get(entry.getKey());
            if (value == null) return false;
            if (entry.getKey() == DataComponents.CUSTOM_DATA && customDataSubset) {
                if (!((CustomData) value).matchedBy(((CustomData) entry.getValue()).copyTag())) return false;
            } else if (entry.getKey() == DataComponents.CUSTOM_DATA && offered.customDataSubset
                    || !entry.getValue().equals(value)) {
                return false;
            }
        }
        return true;
    }

    Optional<ItemComponents> intersect(ItemComponents other) {
        if (includes(other)) return Optional.of(other);
        if (other.includes(this)) return Optional.of(this);
        if (exact || other.exact || customDataSubset != other.customDataSubset) return Optional.empty();
        Map<DataComponentType<?>, Object> merged = new LinkedHashMap<>(values);
        for (var entry : other.values.entrySet()) {
            Object old = merged.putIfAbsent(entry.getKey(), entry.getValue());
            if (old != null && !old.equals(entry.getValue())) return Optional.empty();
        }
        DataComponentMap.Builder builder = DataComponentMap.builder();
        merged.forEach((type, value) -> set(builder, type, value));
        return Optional.of(new ItemComponents(builder.build(), false, customDataSubset));
    }

    private static boolean compatible(Tag one, Tag other) {
        if (one instanceof CompoundTag first && other instanceof CompoundTag second) {
            for (String key : first.getAllKeys()) {
                if (second.contains(key) && !compatible(first.get(key), second.get(key))) return false;
            }
            return true;
        }
        // Nonempty NBT subset lists can share a union; an empty list only matches an empty list.
        if (one instanceof ListTag first && other instanceof ListTag second) {
            return first.isEmpty() == second.isEmpty()
                && (first.isEmpty() || first.getElementType() == second.getElementType());
        }
        return one.equals(other);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ItemComponents components && exact == components.exact
            && customDataSubset == components.customDataSubset && values.equals(components.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values, exact, customDataSubset);
    }

    @Override
    public String toString() {
        return (exact ? "=" : customDataSubset ? "~" : "+") + values;
    }
}
