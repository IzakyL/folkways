package io.github.izakyl.folkways.vanilla.wares;

import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import io.github.izakyl.folkways.core.inventory.api.port.PortReader;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Sums what the colony last observed in one or more stores into the compact line a store's card shows. Reads only the
 * published snapshot, never the world, and keeps just the most plentiful kinds, so a whole network stays cheap.
 */
public final class StoreContents {

    private static final Comparator<Map.Entry<Item, Long>> PLENTIFUL = Map.Entry.<Item, Long>comparingByValue()
        .thenComparing(entry -> BuiltInRegistries.ITEM.getKey(entry.getKey()), Comparator.reverseOrder());

    private StoreContents() {
    }

    /**
     * Whether the colony has read the store at least once. A store marked for another read keeps showing what it held
     * when last read rather than going back to "checking", which a store whose goods keep moving would never leave.
     */
    public static boolean known(InventorySnapshot.Store store) {
        return switch (store.state()) {
            case OBSERVED -> true;
            case DIRTY, UNLOADED -> store.version().contents() > 0;
            default -> false;
        };
    }

    /** The stores' goods summed by item; each store is counted once however many of them share it. */
    public static Line.Contents of(Collection<InventorySnapshot.Store> stores) {
        Map<Item, Long> held = new HashMap<>();
        int used = 0;
        int total = 0;
        boolean slotted = true;
        for (InventorySnapshot.Store store : stores) {
            for (PortReader.Entry entry : store.entries()) {
                held.merge(entry.prototype().getItem(), entry.count(), StoreContents::add);
            }
            // Only a plain container's slots are its own; other stores' slot views are made up from their goods.
            if (store.container()) {
                for (SlotSnapshot slot : store.slots()) {
                    if (slot.accessibleSlot().isPresent()) {
                        total++;
                        if (!slot.stackCopy().isEmpty()) {
                            used++;
                        }
                    }
                }
            } else {
                slotted = false;
            }
        }
        // Keeps the few most plentiful in a small heap rather than sorting a network's every kind.
        PriorityQueue<Map.Entry<Item, Long>> most = new PriorityQueue<>(PLENTIFUL);
        for (Map.Entry<Item, Long> entry : held.entrySet()) {
            most.add(entry);
            if (most.size() > Line.Contents.MOST_SHOWN) {
                most.poll();
            }
        }
        List<Map.Entry<Item, Long>> shown = most.stream().sorted(PLENTIFUL.reversed()).toList();
        List<Line.Stack> stacks = shown.stream()
            .map(entry -> new Line.Stack(key(entry.getKey()), entry.getValue())).toList();
        return new Line.Contents(stacks, held.size() - stacks.size(), slotted ? used : 0, slotted ? total : 0);
    }

    private static ResourceLocation key(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    private static long add(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }
}
