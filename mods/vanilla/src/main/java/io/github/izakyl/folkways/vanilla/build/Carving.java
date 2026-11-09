package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.StructureCompat;
import io.github.izakyl.folkways.core.work.support.Leaves;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
        private Map<ItemSpec, Fraction> balance = new LinkedHashMap<>();
        private final Runnable changed;

        Cache(Runnable changed) {
            this.changed = changed;
        }

        void accept(Quote quote) {
            balance = new LinkedHashMap<>(quote.balance());
            changed.run();
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
        List<Need> needs = new ArrayList<>();
        Map<ItemSpec, Fraction> balance = new LinkedHashMap<>(cache.balance);
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
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))).toList(), cache);
    }

    static List<Need> combined(List<Quote> quotes, Cache cache) {
        Map<ItemSpec, Fraction> net = new LinkedHashMap<>();
        quotes.forEach(quote -> quote.net().forEach((material, fraction) -> net.merge(material, fraction, (a, b) -> {
            BigInteger units = a.units().multiply(b.units());
            BigInteger count = a.count().multiply(b.units()).add(b.count().multiply(a.units()));
            BigInteger gcd = count.gcd(units);
            return new Fraction(count.divide(gcd), units.divide(gcd));
        })));
        return priced(new Quote(List.of(), List.of(), 1, Map.of(), net), cache).needs();
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
