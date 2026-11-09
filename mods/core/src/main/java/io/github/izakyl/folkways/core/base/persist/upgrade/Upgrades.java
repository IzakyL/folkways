package io.github.izakyl.folkways.core.base.persist.upgrade;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;

public final class Upgrades {
    static final Upgrades GLOBAL = new Upgrades(DataVersion.BASELINE, DataVersion.CURRENT);
    private final int baseline;
    private final int current;
    private final Map<Surface, TreeMap<Integer, Step>> steps = new EnumMap<>(Surface.class);
    private final Map<Surface, TreeMap<Integer, Map<String, Consumer<CompoundTag>>>> parts =
        new EnumMap<>(Surface.class);
    private boolean frozen;

    // Isolated bounds let tests exercise future chains without registering test steps in production.
    Upgrades(int baseline, int current) {
        if (baseline > current) {
            throw new IllegalArgumentException("Baseline exceeds current data version");
        }
        this.baseline = baseline;
        this.current = current;
    }

    int baseline() {
        return baseline;
    }

    int current() {
        return current;
    }

    public static void register(Step step) {
        GLOBAL.add(step);
    }

    public static void freeze() {
        GLOBAL.seal();
    }

    public static void upgrade(Surface surface, CompoundTag raw, int storedVersion) {
        GLOBAL.run(surface, raw, storedVersion);
    }

    synchronized void add(Step step) {
        Objects.requireNonNull(step);
        if (frozen) {
            throw new IllegalStateException("Save upgrades are frozen");
        }
        if (step.target() <= baseline || step.target() > current) {
            throw new IllegalArgumentException("Upgrade target outside (" + baseline + ", " + current + "]");
        }
        var chain = steps.computeIfAbsent(Objects.requireNonNull(step.surface()), ignored -> new TreeMap<>());
        if (chain.putIfAbsent(step.target(), step) != null) {
            throw new IllegalArgumentException("Duplicate upgrade for " + step.surface() + " at " + step.target());
        }
    }

    /** Compose independent module fragments into the one step for a surface/target. */
    public static void part(String name, Step part) {
        GLOBAL.addPart(name, part.surface(), part.target(), part::apply);
    }

    static void registerBag(String owner, int target, Consumer<CompoundTag> change) {
        GLOBAL.addBag(owner, target, change);
    }

    synchronized void addBag(String owner, int target, Consumer<CompoundTag> change) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(change);
        addPart("keep/" + owner, Surface.COLONY_REGISTRY, target, raw -> KeepSteps.apply(raw, owner, change));
    }

    synchronized void addPart(String name, Surface surface, int target, Consumer<CompoundTag> change) {
        if (frozen) {
            throw new IllegalStateException("Save upgrades are frozen");
        }
        if (target <= baseline || target > current) {
            throw new IllegalArgumentException("Upgrade fragment target outside supported versions");
        }
        var chain = parts.computeIfAbsent(Objects.requireNonNull(surface), ignored -> new TreeMap<>());
        var changes = chain.computeIfAbsent(target, ignored -> new TreeMap<>());
        if (changes.putIfAbsent(Objects.requireNonNull(name), Objects.requireNonNull(change)) != null) {
            throw new IllegalArgumentException("Duplicate upgrade fragment " + name + " for " + surface + " at " + target);
        }
    }

    synchronized void seal() {
        frozen = true;
    }

    synchronized void run(Surface surface, CompoundTag raw, int storedVersion) {
        if (storedVersion < baseline || storedVersion > current) {
            throw new IllegalArgumentException("Folkways save version " + storedVersion
                + " is unsupported (supported: " + baseline + ".." + current + ")");
        }
        var chain = steps.get(surface);
        var fragments = parts.get(surface);
        for (int target = storedVersion + 1; target <= current; target++) {
            if (chain != null && chain.containsKey(target)) {
                chain.get(target).apply(raw);
            }
            if (fragments != null && fragments.containsKey(target)) {
                fragments.get(target).values().forEach(change -> change.accept(raw));
            }
        }
    }

    /** Decode current first, then converted legacy shapes; always encode with the current codec. */
    @SafeVarargs
    public static <A> Codec<A> codec(Codec<A> current, Codec<? extends A>... legacy) {
        var alternatives = java.util.List.of(legacy);
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
                DataResult<Pair<A, T>> result = current.decode(ops, input);
                if (result.result().isPresent()) {
                    return result;
                }
                for (Codec<? extends A> old : alternatives) {
                    DataResult<Pair<A, T>> decoded = old.decode(ops, input)
                        .map(pair -> Pair.of((A) pair.getFirst(), pair.getSecond()));
                    if (decoded.result().isPresent()) {
                        return decoded;
                    }
                }
                return result;
            }

            @Override
            public <T> DataResult<T> encode(A input, DynamicOps<T> ops, T prefix) {
                return current.encode(input, ops, prefix);
            }
        };
    }
}
