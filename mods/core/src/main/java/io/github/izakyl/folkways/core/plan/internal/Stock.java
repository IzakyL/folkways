package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Manifest;
import io.github.izakyl.folkways.core.work.api.Need;
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

    public record Holding(Stash where, ItemStack stack, long count) {

        public Holding(Stash where, ItemStack stack) {
            this(where, stack, stack.getCount());
        }

        public Holding {
            stack = stack.copyWithCount(1);
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }
    }

    public record Lot(Stash where, ItemSpec concrete, long count) {
    }

    private final List<Holding> holdings;
    private final Map<Stash, Room> rooms;
    private final Set<Stash> stashes;
    private final Set<Stash> direct;
    private final Set<Stash> supplied;

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
        this(holdings, slotRooms(holdings, emptyCells), rules, refused, null, null);
    }

    private static Map<Stash, Room> slotRooms(List<Holding> holdings, Map<Stash, Integer> empty) {
        Map<Stash, Room> rooms = new LinkedHashMap<>();
        empty.forEach((where, free) -> rooms.put(where, new Room.Slots(List.of(), free, new Room.Cap(64, 1))));
        for (Holding holding : holdings) {
            Room.Slots slots = (Room.Slots) rooms.getOrDefault(holding.where(),
                new Room.Slots(List.of(), 0, new Room.Cap(64, 1)));
            List<Room.Cell> bound = new ArrayList<>(slots.bound());
            long cap = slots.cap().limit(holding.stack());
            // Legacy callers only supply holdings; observed ports publish their exact bound cells instead.
            if (cap > 0 && holding.count() % cap != 0) {
                bound.add(new Room.Cell(holding.stack(), cap - holding.count() % cap));
            }
            rooms.put(holding.where(), new Room.Slots(bound, slots.open(), slots.cap()));
        }
        return rooms;
    }

    public static Stock observed(List<Holding> holdings, Map<Stash, Room> rooms,
                                 Map<Stash, List<StoreRule>> rules, Map<Stash, Set<Item>> refused) {
        return new Stock(holdings, rooms, rules, refused, null, null);
    }

    private Stock(List<Holding> holdings, Map<Stash, Room> rooms, Map<Stash, List<StoreRule>> rules,
                  Map<Stash, Set<Item>> refused, Set<Stash> direct, Set<Stash> supplied) {
        this.rules = Map.copyOf(rules);
        this.refused = Map.copyOf(refused);
        this.holdings = List.copyOf(holdings);
        this.rooms = Map.copyOf(rooms);
        Set<Stash> known = new LinkedHashSet<>(rooms.keySet());
        for (Holding holding : this.holdings) {
            known.add(holding.where());
        }
        this.stashes = Set.copyOf(known);
        this.direct = direct == null ? this.stashes : Set.copyOf(direct);
        this.supplied = supplied == null ? this.stashes : Set.copyOf(supplied);
        Map<Stash, List<Holding>> grouped = new LinkedHashMap<>();
        for (Holding holding : this.holdings) {
            grouped.computeIfAbsent(holding.where(), key -> new ArrayList<>()).add(holding);
        }
        grouped.replaceAll((where, held) -> List.copyOf(held));
        this.byStash = Map.copyOf(grouped);
    }

    public Stock direct(Set<Stash> stores) {
        return new Stock(holdings, rooms, rules, refused, stores, supplied);
    }

    // Keep physical contents for capacity calculations, while Workshops alone promise withheld goods.
    public Stock supplied(Set<Stash> stores) {
        return new Stock(holdings, rooms, rules, refused, direct, stores);
    }

    public boolean direct(Stash where) {
        return direct.contains(where);
    }

    public Set<Stash> stashes() {
        return stashes;
    }

    public boolean holds(Stash where) {
        return stashes.contains(where);
    }

    public boolean takes(Stash where) {
        return StoreRule.takes(rules.getOrDefault(where, List.of()));
    }

    public boolean stores(Stash where) {
        return StoreRule.stores(rules.getOrDefault(where, List.of()));
    }

    public boolean admits(Stash where, ItemSpec spec) {
        return StoreRule.admits(rules.getOrDefault(where, List.of()), spec) && accepts(where, spec);
    }

    private boolean accepts(Stash where, ItemSpec spec) {
        Set<Item> barred = refused.getOrDefault(where, Set.of());
        return barred.isEmpty() || Collections.disjoint(Goods.members(spec), barred);
    }

    public int preference(Stash where, ItemSpec spec) {
        int priority = 0;
        for (StoreRule rule : rules.getOrDefault(where, List.of())) {
            if (rule instanceof StoreRule.Prefer prefer && Goods.overlap(prefer.goods(), spec)) {
                priority = Math.max(priority, prefer.priority());
            }
        }
        return priority;
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
            if (supplied.contains(holding.where()) && Goods.matches(holding.stack(), spec)) {
                total += holding.count();
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
            if (!supplied.contains(holding.where()) || !Goods.matches(holding.stack(), spec)) {
                continue;
            }
            byStash.computeIfAbsent(holding.where(), key -> new LinkedHashMap<>())
                .merge(Goods.specOf(holding.stack()), holding.count(), Long::sum);
        }
        List<Lot> found = new ArrayList<>();
        byStash.forEach((where, byItem) ->
            byItem.forEach((concrete, count) -> found.add(new Lot(where, concrete, count))));
        return List.copyOf(found);
    }

    public long heldAt(Stash where, ItemSpec spec) {
        return supplied.contains(where) ? storedAt(where, spec) : 0;
    }

    private long storedAt(Stash where, ItemSpec spec) {
        long held = 0;
        for (Holding holding : byStash.getOrDefault(where, List.of())) {
            if (Goods.matches(holding.stack(), spec)) {
                held += holding.count();
            }
        }
        return held;
    }

    public long roomAt(Stash where, ItemSpec spec) {
        return roomAt(where, spec, false);
    }

    public long roomAt(Stash where, ItemSpec spec, boolean directed) {
        if (!stashes.contains(where) || !(directed ? accepts(where, spec) : admits(where, spec))) {
            return 0;
        }
        return roomAfter(where, spec, directed, Map.of(), Map.of());
    }

    // All reservations on one store share its capacity model, even when they name different goods.
    public long roomAfter(Stash where, ItemSpec spec, boolean directed, Map<ItemSpec, Long> puts,
                          Map<ItemSpec, Long> takes) {
        if (!stashes.contains(where) || !(directed ? accepts(where, spec) : admits(where, spec))) {
            return 0;
        }
        Room room = rooms.getOrDefault(where, Room.NONE);
        if (room instanceof Room.Pool pool) {
            long used = puts.values().stream().mapToLong(Long::longValue).sum();
            long ceiling = pool.perKey().map(limits -> Math.max(0,
                limits.getOrDefault(Goods.specOf(Goods.display(spec)), 0L) - used)).orElse(Long.MAX_VALUE);
            long freed = takes.values().stream().mapToLong(Long::longValue).sum();
            long kinds = puts.keySet().stream().filter(key -> storedAt(where, key) == 0 && puts.get(key) > 0).count();
            boolean known = storedAt(where, spec) > 0 || puts.getOrDefault(spec, 0L) > 0;
            return !known && kinds >= pool.newKinds() ? 0
                : Math.min(ceiling, remaining(pool.items(), freed, used));
        }
        Room.Slots slots = (Room.Slots) room;
        long used = 0;
        for (var put : puts.entrySet()) {
            if (put.getKey().equals(spec)) {
                continue;
            }
            long bound = bound(slots, put.getKey());
            used = add(used, cells(Math.max(0, put.getValue() - bound - takes.getOrDefault(put.getKey(), 0L)),
                slots.cap().limit(Goods.display(put.getKey()))));
        }
        long cap = slots.cap().limit(Goods.display(spec));
        long bound = bound(slots, spec);
        long open = Math.max(0, slots.open() - used);
        return remaining(add(bound, multiply(open, cap)), takes.getOrDefault(spec, 0L), puts.getOrDefault(spec, 0L));
    }

    private static long bound(Room.Slots slots, ItemSpec spec) {
        return slots.bound().stream().filter(cell -> Goods.matches(cell.prototype(), spec))
            .mapToLong(Room.Cell::room).reduce(0, Stock::add);
    }

    private static long cells(long count, long limit) {
        return count == 0 ? 0 : limit <= 0 ? Long.MAX_VALUE : (count - 1) / limit + 1;
    }

    private static long multiply(long count, long limit) {
        return count <= 0 || limit <= 0 ? 0 : count > Long.MAX_VALUE / limit ? Long.MAX_VALUE : count * limit;
    }

    private static long add(long one, long other) {
        return one > Long.MAX_VALUE - other ? Long.MAX_VALUE : one + other;
    }

    private static long remaining(long capacity, long freed, long used) {
        return Math.max(0, add(capacity, freed) - used);
    }

    public Manifest manifest(Stash where, ItemSpec spec, long skip, long count) {
        List<ItemStack> result = new ArrayList<>();
        long left = count;
        for (Holding holding : byStash.getOrDefault(where, List.of())) {
            if (!Goods.matches(holding.stack(), spec)) {
                continue;
            }
            ItemStack stack = holding.stack();
            long ignored = Math.min(skip, holding.count());
            skip -= ignored;
            long take = Math.min(left, holding.count() - ignored);
            if (take > 0) {
                left -= take;
                while (take > 0) {
                    int part = (int) Math.min(take, Math.max(1, stack.getMaxStackSize()));
                    result.add(stack.copyWithCount(part));
                    take -= part;
                }
            }
            if (left == 0) {
                break;
            }
        }
        return new Manifest(result, left > 0
            ? List.of(new Need(spec, left)) : List.of());
    }
}
