package io.github.izakyl.folkways.core.inventory.api;

import io.github.izakyl.folkways.core.inventory.api.port.PortReader.Entry;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;

public final class StoreReadings {

    private StoreReadings() {
    }

    public static List<Entry> entries(List<SlotSnapshot> slots) {
        List<Entry> entries = new ArrayList<>();
        for (SlotSnapshot slot : slots) {
            ItemStack stack = slot.stackCopy();
            if (stack.isEmpty() || slot.accessibleSlot().isEmpty()) {
                continue;
            }
            int matching = -1;
            for (int at = 0; at < entries.size(); at++) {
                if (ItemStack.isSameItemSameComponents(entries.get(at).prototype(), stack)) {
                    matching = at;
                    break;
                }
            }
            if (matching < 0) {
                entries.add(new Entry(stack, stack.getCount()));
            } else {
                entries.set(matching, new Entry(stack, entries.get(matching).count() + stack.getCount()));
            }
        }
        return List.copyOf(entries);
    }

    public static Room room(List<SlotSnapshot> slots) {
        List<Room.Cell> bound = slots.stream()
            .filter(slot -> slot.accessibleSlot().isPresent() && !slot.stackCopy().isEmpty())
            .map(slot -> new Room.Cell(slot.stackCopy(),
                Math.max(0L, (long) slot.stackLimit() - slot.stackCopy().getCount()))).toList();
        long open = slots.stream().filter(slot -> slot.accessibleSlot().isPresent()
            && slot.stackCopy().isEmpty()).count();
        return new Room.Slots(bound, open,
            new Room.Cap(slots.stream().mapToLong(SlotSnapshot::stackLimit).max().orElse(64), 1));
    }

    // Bulk counts stay in entries. The compatibility view splits on demand, without allocating a list per stack.
    public static List<SlotSnapshot> synthetic(List<Entry> entries) {
        List<Entry> goods = List.copyOf(entries);
        long count = 0;
        for (Entry entry : goods) {
            int limit = Math.max(1, entry.prototype().getMaxStackSize());
            count = Math.min(Integer.MAX_VALUE, count + (entry.count() - 1) / limit + 1);
        }
        int size = (int) count;
        return new Synthetic(goods, size);
    }

    public static List<SlotSnapshot> immutableSlots(List<SlotSnapshot> slots) {
        return slots instanceof Synthetic ? slots : List.copyOf(slots);
    }

    private static final class Synthetic extends AbstractList<SlotSnapshot> {

        private final List<Entry> goods;
        private final int size;

        Synthetic(List<Entry> goods, int size) {
            this.goods = goods;
            this.size = size;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public SlotSnapshot get(int index) {
            java.util.Objects.checkIndex(index, size);
            long offset = index;
            for (Entry entry : goods) {
                ItemStack stack = entry.prototype();
                int limit = Math.max(1, stack.getMaxStackSize());
                long stacks = (entry.count() - 1) / limit + 1;
                if (offset < stacks) {
                    return new SlotSnapshot(index, index,
                        stack.copyWithCount((int) Math.min(limit, entry.count() - offset * limit)), limit);
                }
                offset -= stacks;
            }
            throw new IndexOutOfBoundsException(index);
        }
    }

    public static boolean same(List<Entry> one, List<Entry> other) {
        return one.size() == other.size() && one.stream().allMatch(entry -> other.stream().anyMatch(candidate ->
            entry.count() == candidate.count()
                && ItemStack.isSameItemSameComponents(entry.prototype(), candidate.prototype())));
    }
}
