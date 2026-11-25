package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.BlockData;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.StructureCompat;
import io.github.izakyl.folkways.core.work.support.Leaves;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/** Exact volume accounting in whole-block fractions, shared by every node of an order. */
final class Carving {
    record Quote(List<Need> needs, List<Amount> leaves, int ticks, Map<ItemSpec, Fraction> balance,
            Map<ItemSpec, Fraction> net) {
    }

    record Fraction(BigInteger count, BigInteger units) {
        Fraction plus(int count, int units) {
            BigInteger denominator = this.units.multiply(BigInteger.valueOf(units));
            BigInteger numerator = this.count.multiply(BigInteger.valueOf(units))
                .add(BigInteger.valueOf(count).multiply(this.units));
            BigInteger gcd = numerator.gcd(denominator);
            return new Fraction(numerator.divide(gcd), denominator.divide(gcd));
        }
    }

    static final class Cache {
        static final int WHOLE_STACKS = 9;
        private static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);

        // Reservations mark stock in the balance. A save or refund still sees every block.
        private final Map<UUID, Map<ItemSpec, Integer>> reserved = new LinkedHashMap<>();
        private final Map<ItemSpec, Integer> promised = new LinkedHashMap<>();
        private Map<ItemSpec, Fraction> balance = new LinkedHashMap<>();
        private final Runnable changed;

        Cache(Runnable changed) {
            this.changed = changed;
        }

        List<ItemStack> accept(Quote quote) {
            Map<ItemSpec, Integer> before = stock();
            balance = new LinkedHashMap<>(quote.balance());
            promised.forEach(this::add);
            Map<ItemSpec, Integer> gained = new LinkedHashMap<>();
            stock().forEach((item, count) -> {
                int added = count - before.getOrDefault(item, 0);
                if (added > 0) {
                    gained.put(item, added);
                    add(item, -added);
                }
            });
            List<ItemStack> excess = new ArrayList<>();
            gained.forEach((item, added) -> {
                ItemStack stack = Goods.display(item);
                int kept = Math.min(added, room(stack));
                add(item, kept);
                for (int left = added - kept; left > 0; left -= stack.getMaxStackSize()) {
                    excess.add(stack.copyWithCount(Math.min(left, stack.getMaxStackSize())));
                }
            });
            changed.run();
            return excess;
        }

        private void add(ItemSpec item, int count) {
            Fraction next = balance.getOrDefault(item, ZERO).plus(count, 1);
            if (next.count().signum() == 0) {
                balance.remove(item);
            } else {
                balance.put(item, next);
            }
        }

        private int whole(Fraction fraction) {
            return fraction.count().divide(fraction.units()).intValueExact();
        }

        Map<ItemSpec, Integer> stock() {
            Map<ItemSpec, Integer> stock = new LinkedHashMap<>();
            balance.forEach((item, fraction) -> {
                int count = whole(fraction);
                if (count > 0) {
                    stock.put(item, count);
                }
            });
            return stock;
        }

        Map<ItemSpec, Integer> available(UUID stay, boolean shared) {
            Map<ItemSpec, Integer> available = new LinkedHashMap<>(reserved.getOrDefault(stay, Map.of()));
            if (shared) {
                stock().forEach((item, count) -> {
                    int publicCount = count - promised.getOrDefault(item, 0);
                    if (publicCount > 0) {
                        available.merge(item, publicCount, Integer::sum);
                    }
                });
            }
            return available;
        }

        void reserve(UUID stay, Map<ItemSpec, Integer> goods) {
            if (!goods.isEmpty()) {
                reserved.put(stay, goods);
                goods.forEach((item, count) -> promised.merge(item, count, Integer::sum));
            }
        }

        boolean release(UUID stay) {
            Map<ItemSpec, Integer> goods = reserved.remove(stay);
            if (goods == null || goods.isEmpty()) {
                return false;
            }
            goods.forEach(this::unpromise);
            return true;
        }

        private void unpromise(ItemSpec item, int count) {
            promised.compute(item, (key, have) -> have == count ? null : have - count);
        }

        void take(UUID stay, ItemSpec item, int count) {
            Map<ItemSpec, Integer> own = reserved.get(stay);
            int used = own == null ? 0 : Math.min(count, own.getOrDefault(item, 0));
            if (used > 0) {
                own.compute(item, (key, have) -> have == used ? null : have - used);
                unpromise(item, used);
            }
            add(item, -count);
            changed.run();
        }

        int deposit(ItemStack stack, int limit) {
            ItemSpec item = ItemSpec.of(stack.getItem());
            if (!ItemStack.isSameItemSameComponents(stack, Goods.display(item))) {
                item = Goods.specOf(stack);
            }
            int count = Math.min(Math.min(stack.getCount(), limit), room(stack));
            if (count <= 0) {
                return 0;
            }
            add(item, count);
            changed.run();
            return count;
        }

        // Count whole blocks by the space they would take if stacked together.
        private int room(ItemStack stack) {
            Fraction left = ZERO.plus(WHOLE_STACKS, 1);
            for (var entry : stock().entrySet()) {
                int size = Goods.stackSize(entry.getKey());
                if (size > 0) {
                    left = left.plus(-entry.getValue(), size);
                }
            }
            return left.count().max(BigInteger.ZERO).multiply(BigInteger.valueOf(stack.getMaxStackSize()))
                .divide(left.units()).intValueExact();
        }

        private Map<ItemSpec, Fraction> pricingBalance(boolean fractionsOnly) {
            Map<ItemSpec, Fraction> available = new LinkedHashMap<>();
            balance.forEach((item, fraction) -> available.put(item,
                fraction.plus(-(fractionsOnly ? whole(fraction) : promised.getOrDefault(item, 0)), 1)));
            return available;
        }

        CompoundTag save() {
            return Writer.of()
                .children("materials", balance.entrySet(), entry ->
                    Writer.of()
                        .blob("material", entry.getKey().save())
                        .string("count", entry.getValue().count().toString())
                        .string("units", entry.getValue().units().toString()).tag()).tag();
        }

        void load(Reader reader) {
            for (var entry : reader.requiredChildren("materials")) {
                entry.child("material").flatMap(ItemSpec::load).ifPresent(material -> balance.put(material,
                    new Fraction(new BigInteger(entry.string("count").orElseThrow()),
                        new BigInteger(entry.string("units").orElseThrow()))));
            }
        }

        List<Amount> drain() {
            List<Amount> refund = balance.entrySet().stream().map(entry -> new Amount(entry.getKey(),
                entry.getValue().count().divide(entry.getValue().units()).longValueExact()))
                .filter(amount -> amount.count() > 0).toList();
            balance.clear();
            reserved.clear();
            promised.clear();
            changed.run();
            return refund;
        }
    }

    private Carving() {
    }

    static List<Need> estimate(List<StructureCompat.Voxels> volumes) {
        Map<ItemSpec, Fraction> net = new LinkedHashMap<>();
        volumes.forEach(volume -> add(net, volume, 1));
        return net.entrySet().stream().map(entry -> {
            Fraction fraction = entry.getValue();
            long blocks = fraction.count().add(fraction.units()).subtract(BigInteger.ONE)
                .divide(fraction.units()).longValueExact();
            return new Need(entry.getKey(), blocks);
        }).toList();
    }

    static boolean cell(LevelReader level, BuildTarget target, BlockPos cell) {
        return StructureCompat.of(target.wanted(cell)).isPresent()
            || StructureCompat.of(level.getBlockState(cell)).isPresent();
    }

    static boolean replacement(LevelReader level, BuildTarget target, BlockPos cell) {
        BlockState goal = target.wanted(cell);
        return !goal.isAir() && StructureCompat.of(goal).isEmpty()
            && StructureCompat.of(level.getBlockState(cell))
                .map(handler -> handler.solid(goal).isEmpty()).orElse(false);
    }

    static Quote quote(LevelReader level, Map<BlockPos, BlockState> after, Function<BlockPos, BlockData> data) {
        return quote(level, after, data, new Cache(() -> { }));
    }

    static Quote quote(LevelReader level, Map<BlockPos, BlockState> after, Function<BlockPos, BlockData> data,
            Cache cache) {
        Map<ItemSpec, Fraction> net = new LinkedHashMap<>();
        List<BlockState> rubble = new ArrayList<>();
        double changed = 0;
        for (var cell : after.entrySet()) {
            BlockPos pos = cell.getKey();
            BlockState goal = cell.getValue();
            BlockState standing = level.getBlockState(pos);
            var integration = StructureCompat.of(goal).or(() -> StructureCompat.of(standing)).or(StructureCompat::carver);
            if (integration.isEmpty()) {
                if (BuildJob.inTheWay(standing)) {
                    rubble.add(standing);
                }
                continue;
            }
            var handler = integration.get();
            var target = volume(handler, goal, data.apply(pos));
            var current = StructureCompat.of(standing).map(found -> found.voxels(level.getBlockEntity(pos)))
                .orElseGet(() -> solid(handler, standing));
            add(net, target, 1);
            add(net, current, -1);
            if (BuildJob.inTheWay(standing) && current.counts().isEmpty()) {
                rubble.add(standing);
                changed++;
            }
            if (StructureCompat.of(goal).isPresent() && StructureCompat.of(standing).isPresent()) {
                changed += handler.changed(level.getBlockEntity(pos), data.apply(pos).payload(goal));
            } else {
                double overlap = 0;
                for (var material : target.counts().entrySet()) {
                    overlap += Math.min(material.getValue() / (double) target.perBlock(),
                        current.counts().getOrDefault(material.getKey(), 0) / (double) current.perBlock());
                }
                changed += target.counts().values().stream().mapToInt(Integer::intValue).sum() / (double) target.perBlock()
                    + current.counts().values().stream().mapToInt(Integer::intValue).sum() / (double) current.perBlock()
                    - 2 * overlap;
            }
        }
        int ticks = Math.max(1, Math.min(320, (int) Math.ceil(changed * BuildJob.LAY_TICKS)));
        return priced(new Quote(List.of(), Leaves.ofBlocks(rubble), ticks, Map.of(), Map.copyOf(net)), cache);
    }

    static Quote priced(Quote quote, Cache cache) {
        return priced(quote, cache.pricingBalance(false));
    }

    private static Quote priced(Quote quote, Map<ItemSpec, Fraction> balance) {
        List<Need> needs = new ArrayList<>();
        quote.net().forEach((material, fraction) -> {
            Fraction old = balance.getOrDefault(material, new Fraction(BigInteger.ZERO, BigInteger.ONE));
            BigInteger units = fraction.units().multiply(old.units());
            BigInteger deficit = fraction.count().multiply(old.units()).subtract(old.count().multiply(fraction.units()));
            BigInteger blocks = deficit.signum() > 0
                ? deficit.add(units).subtract(BigInteger.ONE).divide(units) : BigInteger.ZERO;
            if (blocks.signum() > 0) {
                needs.add(new Need(material, blocks.longValueExact()));
            }
            BigInteger left = blocks.multiply(units).subtract(deficit);
            BigInteger gcd = left.gcd(units);
            balance.put(material, new Fraction(left.divide(gcd), units.divide(gcd)));
        });
        return new Quote(List.copyOf(needs), quote.leaves(), quote.ticks(), Map.copyOf(balance), quote.net());
    }

    static List<Need> funding(List<Quote> quotes, Cache cache) {
        return combined(quotes.stream().map(quote -> new Quote(List.of(), List.of(), 1, Map.of(),
            quote.net().entrySet().stream().filter(entry -> entry.getValue().count().signum() > 0)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))).toList(), cache.pricingBalance(true));
    }

    static List<Need> combined(List<Quote> quotes, Cache cache) {
        return combined(quotes, cache.pricingBalance(true));
    }

    private static List<Need> combined(List<Quote> quotes, Map<ItemSpec, Fraction> balance) {
        Map<ItemSpec, Fraction> net = new LinkedHashMap<>();
        quotes.forEach(quote -> quote.net().forEach((material, fraction) -> net.merge(material, fraction, (a, b) -> {
            BigInteger units = a.units().multiply(b.units());
            BigInteger count = a.count().multiply(b.units()).add(b.count().multiply(a.units()));
            BigInteger gcd = count.gcd(units);
            return new Fraction(count.divide(gcd), units.divide(gcd));
        })));
        return priced(new Quote(List.of(), List.of(), 1, Map.of(), net), balance).needs();
    }

    static StructureCompat.Voxels volume(StructureCompat.Handler handler, BlockState state, BlockData data) {
        if (StructureCompat.of(state).isPresent()) {
            return handler.voxels(data.payload(state));
        }
        var volume = solid(handler, state);
        if (!state.isAir() && volume.counts().isEmpty()) {
            throw StructureCompat.invalid();
        }
        return volume;
    }

    static StructureCompat.Voxels solid(StructureCompat.Handler handler, BlockState state) {
        return new StructureCompat.Voxels(1, handler.solid(state).map(material -> Map.of(material,
            1)).orElse(Map.of()));
    }

    private static void add(Map<ItemSpec, Fraction> net, StructureCompat.Voxels volume, int sign) {
        volume.counts().forEach((material, count) -> net.compute(material, (key, old) ->
            (old == null ? new Fraction(BigInteger.ZERO, BigInteger.ONE) : old)
                .plus(sign * count, volume.perBlock())));
    }
}
