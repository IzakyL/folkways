package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.work.Need;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class Stock {

    public record Holding(Stash where, ItemStack stack) {
    }

    public record Lot(Stash where, ItemSpec concrete, long count) {
    }

    private final List<Holding> holdings;
    private final Map<Stash, Integer> emptyCells;
    private final Set<Stash> stashes;

    private final Map<Stash, List<Holding>> byStash;
    private final Map<Stash, List<StoreRule>> rules;
    private final Map<Stash, Set<Item>> refused;

    private final Map<ItemSpec, Long> counted = new ConcurrentHashMap<>();
    private final Map<ItemSpec, List<Lot>> lots = new ConcurrentHashMap<>();

    public Stock(List<Holding> holdings, Map<Stash, Integer> emptyCells) {
        this(holdings, emptyCells, Map.of());
    }

    public Stock(List<Holding> holdings, Map<Stash, Integer> emptyCells, Map<Stash, List<StoreRule>> rules) {
        this(holdings, emptyCells, rules, Map.of());
    }

    // `refused` holds, per store, the items the container itself will not take in any slot.
    public Stock(List<Holding> holdings, Map<Stash, Integer> emptyCells, Map<Stash, List<StoreRule>> rules,
                 Map<Stash, Set<Item>> refused) {
        this.rules = Map.copyOf(rules);
        this.refused = Map.copyOf(refused);
        this.holdings = List.copyOf(holdings);
        this.emptyCells = Map.copyOf(emptyCells);
        Set<Stash> known = new LinkedHashSet<>(emptyCells.keySet());
        for (Holding holding : this.holdings) {
            known.add(holding.where());
        }
        this.stashes = Set.copyOf(known);
        Map<Stash, List<Holding>> grouped = new LinkedHashMap<>();
        for (Holding holding : this.holdings) {
            grouped.computeIfAbsent(holding.where(), key -> new ArrayList<>()).add(holding);
        }
        grouped.replaceAll((where, held) -> List.copyOf(held));
        this.byStash = Map.copyOf(grouped);
    }

    public Set<Stash> stashes() {
        return stashes;
    }

    public boolean holds(Stash where) {
        return stashes.contains(where);
    }

    public boolean admits(Stash where, ItemSpec spec) {
        Set<Item> barred = refused.getOrDefault(where, Set.of());
        return StoreRule.admits(rules.getOrDefault(where, List.of()), spec)
            && (barred.isEmpty() || Collections.disjoint(Goods.members(spec), barred));
    }

    public boolean reserves(Stash where, ItemSpec spec) {
        return StoreRule.reserves(rules.getOrDefault(where, List.of()), spec);
    }

    // Whether the store is kept stocked with goods other than these: a place for those, not for anything put away.
    public boolean keepsOther(Stash where, ItemSpec spec) {
        for (StoreRule rule : rules.getOrDefault(where, List.of())) {
            if (rule instanceof StoreRule.Reserve reserve && !Goods.overlap(reserve.goods(), spec)) {
                return true;
            }
        }
        return false;
    }

    public long available(ItemSpec spec) {
        return counted.computeIfAbsent(spec, this::count);
    }

    private long count(ItemSpec spec) {
        long total = 0;
        for (Holding holding : holdings) {
            if (Goods.matches(holding.stack(), spec)) {
                total += holding.stack().getCount();
            }
        }
        return total;
    }

    public List<Lot> lotsOf(ItemSpec spec) {
        return lots.computeIfAbsent(spec, this::findLots);
    }

    private List<Lot> findLots(ItemSpec spec) {
        Map<Stash, Map<ItemSpec, Long>> byStash = new LinkedHashMap<>();
        for (Holding holding : holdings) {
            if (!Goods.matches(holding.stack(), spec)) {
                continue;
            }
            byStash.computeIfAbsent(holding.where(), key -> new LinkedHashMap<>())
                .merge(Goods.specOf(holding.stack()), (long) holding.stack().getCount(), Long::sum);
        }
        List<Lot> found = new ArrayList<>();
        byStash.forEach((where, byItem) ->
            byItem.forEach((concrete, count) -> found.add(new Lot(where, concrete, count))));
        return List.copyOf(found);
    }

    public long heldAt(Stash where, ItemSpec spec) {
        long held = 0;
        for (Holding holding : byStash.getOrDefault(where, List.of())) {
            if (Goods.matches(holding.stack(), spec)) {
                held += holding.stack().getCount();
            }
        }
        return held;
    }

    public long roomAt(Stash where, ItemSpec spec) {
        if (!stashes.contains(where) || !admits(where, spec)) {
            return 0;
        }
        long room = (long) emptyCells.getOrDefault(where, 0) * Math.max(1, Goods.stackSize(spec));
        for (Holding holding : byStash.getOrDefault(where, List.of())) {
            if (Goods.matches(holding.stack(), spec)) {
                ItemStack stack = holding.stack();
                room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return room;
    }

    public Manifest manifest(Stash where, ItemSpec spec, long skip, long count) {
        List<ItemStack> result = new ArrayList<>();
        long left = count;
        for (Holding holding : byStash.getOrDefault(where, List.of())) {
            if (!Goods.matches(holding.stack(), spec)) {
                continue;
            }
            ItemStack stack = holding.stack();
            long ignored = Math.min(skip, stack.getCount());
            skip -= ignored;
            int take = (int) Math.min(left, stack.getCount() - ignored);
            if (take > 0) {
                result.add(stack.copyWithCount(take));
                left -= take;
            }
            if (left == 0) {
                break;
            }
        }
        return new Manifest(result, left > 0
            ? List.of(new Need(spec, left)) : List.of());
    }
}
