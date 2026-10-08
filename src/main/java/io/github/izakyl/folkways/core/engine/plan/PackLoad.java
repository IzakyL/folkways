package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Amount;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// What a route carries, counted in pack cells the way the schedule lays it: per kind of goods, so many small loads
// of one thing share their stacks, and goods a later step hands on free the cells an earlier one filled.
public final class PackLoad {

    private PackLoad() {
    }

    // Whether work done in this order keeps within the pack at every step, from `base` cells used now.
    public static boolean fits(int base, int capacity, List<List<Amount>> inOrder) {
        Map<ItemSpec, Long> held = new LinkedHashMap<>();
        for (List<Amount> carrying : inOrder) {
            for (Amount amount : carrying) {
                held.merge(amount.spec(), amount.count(), Long::sum);
            }
            held.values().removeIf(count -> count == 0);
            if (base + cells(held) > capacity) {
                return false;
            }
        }
        return true;
    }

    static int cells(Map<ItemSpec, Long> carrying) {
        int held = 0;
        for (Map.Entry<ItemSpec, Long> amount : carrying.entrySet()) {
            held += cellsOf(amount.getKey(), amount.getValue());
        }
        return held;
    }

    private static int cellsOf(ItemSpec spec, long count) {
        int stack = Math.max(1, Goods.stackSize(spec));
        long cells = (Math.abs(count) + stack - 1) / stack;
        return (int) (count < 0 ? -cells : cells);
    }
}
