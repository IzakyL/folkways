package io.github.izakyl.folkways.core.resident.api;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.minecraft.resources.ResourceLocation;

/** Optional item integrations expose real nested contents and native inventory operations. */
public interface KitProvider {

    record Held(ResourceLocation item, int count, List<Held> children, boolean ticking) {
        public Held {
            children = List.copyOf(children);
        }

        public Held(ResourceLocation item, int count) {
            this(item, count, List.of(), true);
        }

        public Held(ResourceLocation item, int count, List<Held> children) {
            this(item, count, children, true);
        }
    }

    /** A native inventory, including its per-item slot limits and filters. */
    interface Inventory extends IItemHandlerModifiable {
        int limit(int slot, ItemStack stack);
    }

    /** Child inventory at a path through the worn root; all mutations must enforce native restrictions. */
    default Optional<IItemHandler> children(ItemStack root, List<Integer> path) {
        return Optional.empty();
    }

    default boolean active(ItemStack root, List<Integer> path) {
        return true;
    }

    default Optional<Inventory> inventory(ItemStack root) {
        return Optional.empty();
    }

    /** Effective roots in native processing order; integrations may include ordinary inventory items. */
    default List<ItemStack> roots(Body body, List<ItemStack> worn) {
        return worn.stream().filter(stack -> accepts(BuiltInRegistries.ITEM.getKey(stack.getItem()))).toList();
    }

    /** Native ticking can be narrower than event-driven upgrade eligibility. */
    default boolean ticking(Body body, ItemStack root) {
        return true;
    }

    boolean accepts(ResourceLocation item);

    default Optional<Component> refusal(ResourceLocation item) {
        return Optional.empty();
    }

    /** Whether this identity can own child entries, even when its configured slot count is zero. */
    default boolean parent(ResourceLocation item) {
        return false;
    }

    default boolean acceptsChild(ResourceLocation parent, ResourceLocation child) {
        return false;
    }

    default int childSlots(ResourceLocation parent) {
        return 0;
    }

    /**
     * What this parent's child slots are called where they are counted, such as "Upgrade slots"; empty leaves Core's
     * generic word.
     */
    default Optional<Component> childSlotsName(ResourceLocation parent) {
        return Optional.empty();
    }

    default boolean requiresParent(ResourceLocation item) {
        return false;
    }

    KitCapabilities capabilities(List<Held> held);
}
