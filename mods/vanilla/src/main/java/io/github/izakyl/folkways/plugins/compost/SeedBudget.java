package io.github.izakyl.folkways.plugins.compost;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** A destructive consumer's allowance over one fresh, physical inventory snapshot. */
public final class SeedBudget {
    public record Location(Optional<WorldPos> store, Optional<UUID> resident) {
        public static final Location UNKNOWN = new Location(Optional.empty(), Optional.empty());
        public Location {
            Objects.requireNonNull(store);
            Objects.requireNonNull(resident);
            if (store.isPresent() && resident.isPresent()) throw new IllegalArgumentException("two physical locations");
        }
        public static Location store(WorldPos at) { return new Location(Optional.of(at), Optional.empty()); }
        public static Location pack(UUID resident) { return new Location(Optional.empty(), Optional.of(resident)); }
    }

    // Views of the same physical container (including the two halves of a double chest) share one identity.
    public record Stock(String identity, Location at, List<ItemStack> stacks) {
        public Stock {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(at);
            stacks = stacks.stream().map(ItemStack::copy).toList();
        }
    }

    public record Claim(UUID node, Location at, ItemSpec goods, long count) {
        public Claim {
            Objects.requireNonNull(node);
            Objects.requireNonNull(at);
            Objects.requireNonNull(goods);
            if (count < 0) throw new IllegalArgumentException("negative claim");
        }
    }

    // Only outstanding demands not already represented by claims belong here: e.g. unplanned sowing cells.
    public record FutureNeed(String identity, ItemSpec goods, long count) {
        public FutureNeed {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(goods);
            if (count < 0) throw new IllegalArgumentException("negative future need");
        }
    }

    public record StockTarget(String identity, ItemSpec goods, long count) {
        public StockTarget {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(goods);
            if (count < 0) throw new IllegalArgumentException("negative stock target");
        }
    }

    public record Snapshot(List<Stock> stock, List<Claim> claims, List<FutureNeed> future,
                           Map<Item, Long> maintainTargets, List<StockTarget> targets, boolean complete) {
        public Snapshot(List<Stock> stock, List<Claim> claims, List<FutureNeed> future, Map<Item, Long> maintainTargets) {
            this(stock, claims, future, maintainTargets, List.of(), true);
        }
        public Snapshot {
            stock = List.copyOf(stock);
            claims = List.copyOf(claims);
            future = List.copyOf(future);
            maintainTargets = Map.copyOf(maintainTargets);
            targets = List.copyOf(targets);
            if (maintainTargets.values().stream().anyMatch(count -> count < 0)) {
                throw new IllegalArgumentException("negative maintain target");
            }
        }
    }

    public record Reserve(List<Item> alternatives, ItemSpec goods, long count, long missing) {
        public Reserve { alternatives = List.copyOf(alternatives); }
    }

    public record Tally(long physical, long committed, long retained, long compostable, long missing) {
    }

    private final Supplier<Snapshot> snapshots;
    private final Supplier<Set<Item>> seeds;
    private final ToIntFunction<Item> retained;

    public SeedBudget(Supplier<Snapshot> snapshots, Supplier<Set<Item>> seeds) {
        this(snapshots, seeds, item -> item.getDefaultInstance().getMaxStackSize());
    }

    public SeedBudget(Supplier<Snapshot> snapshots, Supplier<Set<Item>> seeds, ToIntFunction<Item> retained) {
        this.snapshots = Objects.requireNonNull(snapshots);
        this.seeds = Objects.requireNonNull(seeds);
        this.retained = Objects.requireNonNull(retained);
    }

    private long reserve(List<Item> alternatives) {
        return alternatives.stream().mapToLong(item -> Math.max(0, retained.applyAsInt(item))).max().orElse(0);
    }

    public Tally tally(Item seed) { return tally(seed, ItemStack.EMPTY, Set.of()); }

    public boolean complete() { return snapshots.get().complete(); }

    public List<Reserve> reserves() {
        if (!complete()) return List.of();
        return purposes(seeds.get()).stream().map(alternatives -> {
            Item first = alternatives.getFirst();
            return new Reserve(alternatives, ItemSpec.anyOf(alternatives.stream()
                .map(item -> ItemSpec.of(item)).toList()), reserve(alternatives),
                tally(first).missing());
        }).toList();
    }

    // Core has drawn the current input before commit. Add that real escrow back to its former physical
    // total and exclude only this consuming action's claim, then settle consumption or return it unused.
    public boolean permits(UUID consumingNode, ItemStack escrow) {
        return !escrow.isEmpty() && tally(escrow.getItem(), escrow, Set.of(consumingNode)).compostable() >= escrow.getCount();
    }

    public Tally tally(Item seed, ItemStack escrow, Set<UUID> consumingNodes) {
        Snapshot snapshot = snapshots.get();
        if (!seeds.get().contains(seed)) return new Tally(0, 0, 0, 0, 0);
        Map<String, Stock> distinct = new LinkedHashMap<>();
        snapshot.stock().forEach(stock -> distinct.putIfAbsent(stock.identity(), stock));
        List<Unit> units = new ArrayList<>();
        distinct.values().forEach(stock -> stock.stacks().stream().filter(stack -> !stack.isEmpty())
            .forEach(stack -> units.add(new Unit(stock.at(), stack))));
        if (!escrow.isEmpty()) units.add(new Unit(Location.UNKNOWN, escrow));
        ItemSpec wanted = escrow.isEmpty() ? ItemSpec.of(seed) : Goods.specOf(escrow);
        long physical = units.stream().filter(unit -> Goods.matches(unit.stack, wanted)).mapToLong(unit -> unit.free).sum();
        record ClaimKey(UUID node, Location at, ItemSpec goods) { }
        Map<ClaimKey, Long> claims = new LinkedHashMap<>();
        for (Claim claim : snapshot.claims()) {
            if (!consumingNodes.contains(claim.node())) {
                claims.merge(new ClaimKey(claim.node(), claim.at(), claim.goods()), claim.count(), Math::max);
            }
        }
        // Bound claims consume only goods still present at their physical location. A store draw and its
        // later pack transit never create two stocks; unknown claims conservatively consume remaining units.
        claims.forEach((key, count) -> {
            if (!key.at().equals(Location.UNKNOWN)) allocate(units, key.at(), key.goods(), count);
        });
        claims.forEach((key, count) -> {
            if (key.at().equals(Location.UNKNOWN)) allocate(units, Location.UNKNOWN, key.goods(), count);
        });
        Map<String, FutureNeed> future = new LinkedHashMap<>();
        for (FutureNeed need : snapshot.future()) {
            FutureNeed old = future.get(need.identity());
            if (old == null || need.count() > old.count()) future.put(need.identity(), need);
        }
        future.values().forEach(need -> allocate(units, Location.UNKNOWN, need.goods(), need.count()));
        long free = units.stream().filter(unit -> Goods.matches(unit.stack, wanted)).mapToLong(unit -> unit.free).sum();
        Map<ItemSpec, Long> available = new LinkedHashMap<>();
        units.forEach(unit -> available.merge(Goods.specOf(unit.stack), unit.free, Long::sum));
        Map<ItemSpec, Long> baseline = new LinkedHashMap<>();
        long missing = 0;
        for (List<Item> purpose : purposes(seeds.get())) {
            long left = reserve(purpose);
            for (Item alternative : purpose) {
                for (Map.Entry<ItemSpec, Long> entry : available.entrySet()) {
                    if (!Goods.overlap(entry.getKey(), ItemSpec.of(alternative))) continue;
                    long kept = Math.min(left, entry.getValue());
                    baseline.put(entry.getKey(), kept);
                    left -= kept;
                }
            }
            if (purpose.getFirst() == seed) missing = left;
        }
        Map<String, StockTarget> targets = new LinkedHashMap<>();
        snapshot.targets().forEach(target -> targets.merge(target.identity(), target,
            (before, next) -> before.count() >= next.count() ? before : next));
        snapshot.maintainTargets().forEach((item, count) -> targets.put("item/" + item,
            new StockTarget("item/" + item, ItemSpec.of(item), count)));
        Map<ItemSpec, Long> ordered = new LinkedHashMap<>();
        for (StockTarget target : targets.values()) {
            long left = target.count();
            // Explicit stock targets first reuse the purpose reserve. Different target boxes still
            // allocate distinct units, and a tag/anyOf target protects its total only once.
            for (int pass = 0; pass < 2 && left > 0; pass++) {
                for (Map.Entry<ItemSpec, Long> entry : available.entrySet()) {
                    if (!Goods.overlap(entry.getKey(), target.goods())) continue;
                    long limit = pass == 0 ? baseline.getOrDefault(entry.getKey(), 0L) : entry.getValue();
                    long room = Math.max(0, limit - ordered.getOrDefault(entry.getKey(), 0L));
                    long take = Math.min(room, left);
                    ordered.merge(entry.getKey(), take, Long::sum);
                    left -= take;
                    if (left == 0) break;
                }
            }
        }
        long retained = available.keySet().stream().filter(spec -> Goods.overlap(spec, wanted))
            .mapToLong(spec -> Math.max(baseline.getOrDefault(spec, 0L), ordered.getOrDefault(spec, 0L))).sum();
        return new Tally(physical, physical - free, retained, snapshot.complete() ? Math.max(0, free - retained) : 0,
            missing);
    }

    private static List<List<Item>> purposes(Set<Item> seeds) {
        return SeedReserves.purposes(seeds);
    }

    private static void allocate(List<Unit> units, Location where, ItemSpec goods, long count) {
        long left = count;
        for (Unit unit : units) {
            if (left == 0) break;
            if ((!where.equals(Location.UNKNOWN) && !where.equals(unit.at)) || !Goods.matches(unit.stack, goods)) continue;
            long taken = Math.min(unit.free, left);
            unit.free -= taken;
            left -= taken;
        }
    }

    private static final class Unit {
        final Location at;
        final ItemStack stack;
        long free;
        Unit(Location at, ItemStack stack) {
            this.at = at;
            this.stack = stack;
            free = stack.getCount();
        }
    }
}
