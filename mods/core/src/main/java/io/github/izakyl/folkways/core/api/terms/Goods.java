package io.github.izakyl.folkways.core.api.terms;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class Goods {

    private static final java.util.Map<ItemSpec, Integer> STACK_SIZES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<ItemSpec, java.util.Set<Item>> MEMBERS = new java.util.concurrent.ConcurrentHashMap<>();

    private Goods() {
    }

    public static void forgetTags() {
        STACK_SIZES.clear();
        MEMBERS.clear();
    }

    private static Iterable<Holder<Item>> membersOf(TagKey<Item> tag) {
        return BuiltInRegistries.ITEM.getTag(tag).map(named -> (Iterable<Holder<Item>>) named)
            .orElseGet(List::of);
    }

    public static boolean matches(ItemStack stack, ItemSpec spec) {
        if (stack.isEmpty()) {
            return false;
        }
        for (ItemSpec alternative : spec.anyOf()) {
            if (matches(stack, alternative)) {
                return true;
            }
        }
        if (spec.tag().isPresent()) {
            return stack.is(spec.tag().get());
        }
        return spec.item().filter(id -> BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)).isPresent();
    }

    public static long countIn(Container container, ItemSpec what) {
        long held = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (matches(stack, what)) {
                held += stack.getCount();
            }
        }
        return held;
    }

    public static int stackSize(ItemSpec spec) {
        Integer known = STACK_SIZES.get(spec);
        if (known != null) {
            return known;
        }
        int size = sizeOf(spec);
        STACK_SIZES.put(spec, size);
        return size;
    }

    private static int sizeOf(ItemSpec spec) {
        if (!spec.anyOf().isEmpty()) {
            int smallest = 0;
            for (ItemSpec alternative : spec.anyOf()) {
                int size = stackSize(alternative);
                if (size > 0 && (smallest == 0 || size < smallest)) {
                    smallest = size;
                }
            }
            return smallest;
        }
        if (spec.tag().isPresent()) {
            int smallest = 0;
            for (Holder<Item> holder : membersOf(spec.tag().get())) {
                int size = holder.value().getDefaultInstance().getMaxStackSize();
                if (size > 0 && (smallest == 0 || size < smallest)) {
                    smallest = size;
                }
            }
            return smallest;
        }
        return spec.item()
            .flatMap(BuiltInRegistries.ITEM::getOptional)
            .map(item -> item.getDefaultInstance().getMaxStackSize())
            .orElse(0);
    }

    public static ItemSpec specOf(ItemStack stack) {
        return ItemSpec.of(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    public static java.util.Set<Item> members(ItemSpec spec) {
        java.util.Set<Item> known = MEMBERS.get(spec);
        if (known != null) {
            return known;
        }
        java.util.Set<Item> found = java.util.Collections.unmodifiableSet(membersIn(spec));
        MEMBERS.put(spec, found);
        return found;
    }

    private static java.util.Set<Item> membersIn(ItemSpec spec) {
        java.util.Set<Item> accepted = new java.util.LinkedHashSet<>();
        if (!spec.anyOf().isEmpty()) {
            for (ItemSpec alternative : spec.anyOf()) {
                accepted.addAll(members(alternative));
            }
            return accepted;
        }
        if (spec.tag().isPresent()) {
            for (Holder<Item> holder : membersOf(spec.tag().get())) {
                accepted.add(holder.value());
            }
            return accepted;
        }
        spec.item().flatMap(BuiltInRegistries.ITEM::getOptional).ifPresent(accepted::add);
        return accepted;
    }

    public static boolean overlap(ItemSpec one, ItemSpec other) {
        return one.equals(other)
            || !java.util.Collections.disjoint(members(one), members(other));
    }

    public static java.util.Optional<ItemSpec> accepting(java.util.function.Predicate<ItemStack> slot) {
        List<ItemSpec> alternatives = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack candidate = new ItemStack(item);
            if (!candidate.isEmpty() && slot.test(candidate)) {
                alternatives.add(specOf(candidate));
            }
        }
        return alternatives.isEmpty()
            ? java.util.Optional.empty()
            : java.util.Optional.of(ItemSpec.anyOf(alternatives));
    }
}
