package io.github.izakyl.folkways.core.api.terms;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

// A subset compares component values, with recursive custom_data matching; exact compares the whole map.
public final class ItemComponents {
    public static final Codec<ItemComponents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        DataComponentMap.CODEC.fieldOf("values").forGetter(ItemComponents::values),
        Codec.BOOL.optionalFieldOf("exact", false).forGetter(ItemComponents::exact)
    ).apply(instance, ItemComponents::new));

    private final Map<DataComponentType<?>, Object> values;
    private final boolean exact;

    private ItemComponents(DataComponentMap components, boolean exact) {
        Map<DataComponentType<?>, Object> values = new LinkedHashMap<>();
        for (TypedDataComponent<?> component : components) {
            values.put(component.type(), component.value());
        }
        this.values = Map.copyOf(values);
        this.exact = exact;
    }

    public static ItemComponents subset(DataComponentMap components) {
        return new ItemComponents(components, false);
    }

    public static ItemComponents exact(ItemStack stack) {
        return new ItemComponents(stack.getComponents(), true);
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
            if (!exact && entry.getKey() == DataComponents.CUSTOM_DATA) {
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
            if (entry.getKey() == DataComponents.CUSTOM_DATA) {
                if (!compatible(((CustomData) entry.getValue()).copyTag(), ((CustomData) second).copyTag())) return false;
            } else if (!entry.getValue().equals(second)) {
                return false;
            }
        }
        return true;
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
        return other instanceof ItemComponents components && exact == components.exact && values.equals(components.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values, exact);
    }

    @Override
    public String toString() {
        return (exact ? "=" : "~") + values;
    }
}
