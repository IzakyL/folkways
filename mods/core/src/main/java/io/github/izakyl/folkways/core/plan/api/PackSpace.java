package io.github.izakyl.folkways.core.plan.api;

import io.github.izakyl.folkways.core.resident.api.Pack;

import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Amount;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** A planning view of real cargo cells. Personal items and tools reserve their entire physical cells. */
public final class PackSpace {
    private record Cell(ItemStack stack, ToIntFunction<ItemStack> limit) {
    }

    private final List<Cell> cells;
    private final int occupied;

    private PackSpace(List<Cell> cells) {
        this.cells = List.copyOf(cells);
        occupied = (int) cells.stream().filter(cell -> !cell.stack().isEmpty()).count();
    }

    public static PackSpace of(Pack pack, List<ItemStack> cargo) {
        List<ItemStack> remaining = cargo.stream().map(ItemStack::copy).toList();
        List<Cell> cells = new ArrayList<>();
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            ItemStack actual = pack.getItem(slot);
            int count = 0;
            for (ItemStack stack : remaining) {
                if (ItemStack.isSameItemSameComponents(actual, stack)) {
                    int used = Math.min(actual.getCount() - count, stack.getCount());
                    count += used;
                    stack.shrink(used);
                }
            }
            // A partly personal stack also reserves the cell; its cargo can still be unloaded normally.
            if (!actual.isEmpty() && count < actual.getCount()) {
                continue;
            }
            if (slot < pack.ownCells()) {
                int limit = pack.slotLimit(slot, new ItemStack(net.minecraft.world.item.Items.STONE));
                cells.add(new Cell(actual.copy(), stack -> Math.min(limit, stack.getMaxStackSize())));
                continue;
            }
            // Evaluate native admission on the server thread. Unknown component variants are refused
            // conservatively rather than consulting a worn inventory from the background solver.
            Map<ItemSpec, Integer> limits = new LinkedHashMap<>();
            for (var item : BuiltInRegistries.ITEM) {
                ItemStack sample = item.getDefaultInstance();
                if (!sample.isEmpty()) {
                    limits.put(ItemSpec.exact(sample), pack.slotLimit(slot, sample));
                }
            }
            for (ItemStack stack : cargo) {
                if (!stack.isEmpty()) {
                    limits.put(ItemSpec.exact(stack), pack.slotLimit(slot, stack));
                }
            }
            Map<ItemSpec, Integer> captured = Map.copyOf(limits);
            cells.add(new Cell(actual.copy(), stack -> stack.isEmpty() ? 0
                : captured.getOrDefault(ItemSpec.exact(stack), 0)));
        }
        return new PackSpace(cells);
    }

    public int size() {
        return cells.size();
    }

    public int free() {
        return size() - occupied;
    }

    public long capacity(ItemSpec goods) {
        ItemStack sample = Goods.display(goods);
        return sample.isEmpty() ? 0 : cells.stream().mapToLong(cell -> Math.max(0, cell.limit()
            .applyAsInt(sample))).sum();
    }

    /** Change in occupied physical cells, or a large refusal value if native limits or filters prevent the load. */
    public int delta(Map<ItemSpec, Long> changes) {
        int used = load(changes, true);
        return used == Integer.MAX_VALUE ? used : used - occupied;
    }

    public int load(Map<ItemSpec, Long> goods) {
        return load(goods, false);
    }

    private int load(Map<ItemSpec, Long> changes, boolean current) {
        List<ItemStack> stacks = cells.stream().map(cell -> current ? cell.stack().copy() : ItemStack.EMPTY).toList();
        for (var amount : changes.entrySet()) {
            if (amount.getValue() >= 0) {
                continue;
            }
            long left = -amount.getValue();
            for (ItemStack stack : stacks) {
                if (left > 0 && Goods.matches(stack, amount.getKey())) {
                    int removed = (int) Math.min(left, stack.getCount());
                    stack.shrink(removed);
                    left -= removed;
                }
            }
        }
        // Empty slots must be replaceable while retaining the snapshot's original stacks.
        List<ItemStack> placed = new ArrayList<>(stacks);
        long unseen = 0;
        for (var amount : changes.entrySet()) {
            long left = amount.getValue();
            if (left <= 0) {
                continue;
            }
            ItemStack sample = Goods.display(amount.getKey());
            if (sample.isEmpty()) {
                // A spec with no concrete item stacks to one and reserves whole empty cells.
                if (left > cells.size() - unseen) {
                    return Integer.MAX_VALUE;
                }
                unseen += left;
                continue;
            }
            for (int pass = 0; pass < 2 && left > 0; pass++) {
                for (int slot = 0; slot < cells.size() && left > 0; slot++) {
                    ItemStack there = placed.get(slot);
                    if (there.isEmpty() != (pass == 1)
                        || !there.isEmpty() && !ItemStack.isSameItemSameComponents(there, sample)) {
                        continue;
                    }
                    int limit = Math.max(0, cells.get(slot).limit().applyAsInt(sample));
                    int used = (int) Math.min(left, Math.max(0, limit - there.getCount()));
                    if (used > 0) {
                        placed.set(slot, sample.copyWithCount(there.getCount() + used));
                        left -= used;
                    }
                }
            }
            if (left > 0) {
                return Integer.MAX_VALUE;
            }
        }
        // Unknown drops reserve whole empty cells, without a concrete item to pass through native filters.
        long used = placed.stream().filter(stack -> !stack.isEmpty()).count();
        return unseen > cells.size() - used ? Integer.MAX_VALUE : (int) (used + unseen);
    }

    public boolean fits(List<List<Amount>> order) {
        Map<ItemSpec, Long> changes = new LinkedHashMap<>();
        for (List<Amount> step : order) {
            for (Amount amount : step) {
                changes.merge(amount.spec(), amount.count(), Long::sum);
            }
            if (delta(changes) == Integer.MAX_VALUE) {
                return false;
            }
        }
        return true;
    }
}
