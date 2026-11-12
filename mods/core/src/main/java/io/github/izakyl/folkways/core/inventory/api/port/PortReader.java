package io.github.izakyl.folkways.core.inventory.api.port;

import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A bounded read of one store, resumed across ticks until done. The colony calls {@link #step} with a budget of
 * work units (one slot, one key, one sampled item each) and reads {@link #result} once it returns true.
 */
public interface PortReader {

    boolean step(int budget);

    PortReading result();

    /**
     * What a read found. `entries` sums equal goods (same item, same components) into one entry each, with counts
     * that may exceed any stack size. `slots` keeps the slot-level view for slot stores, so existing slot readers keep
     * working; stores without slots leave it empty. `refused` names items no part of the store will take however
     * empty, as the port declares them; `refusesBySampling` asks the colony to work that out by sampling instead.
     */
    record PortReading(List<Entry> entries, Optional<List<SlotSnapshot>> slots, Room room, Set<Item> refused,
                       boolean refusesBySampling) {

        public PortReading {
            entries = List.copyOf(entries);
            slots = slots.map(List::copyOf);
            Objects.requireNonNull(room, "room");
            refused = Set.copyOf(refused);
        }
    }

    record Entry(ItemStack prototype, long count) {

        public Entry {
            Objects.requireNonNull(prototype, "prototype");
            if (prototype.isEmpty() || count < 1) {
                throw new IllegalArgumentException("an entry holds goods");
            }
            prototype = prototype.copyWithCount(1);
        }

        @Override
        public ItemStack prototype() {
            return prototype.copy();
        }
    }
}
