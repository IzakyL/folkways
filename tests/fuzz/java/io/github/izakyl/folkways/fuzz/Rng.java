package io.github.izakyl.folkways.fuzz;

import java.util.List;

/** SplitMix64: a case is its seed, so the same seed draws the same case on every JVM. */
public final class Rng {

    private long state;

    public Rng(long seed) {
        this.state = seed;
    }

    public static long mix(long seed, long salt) {
        long z = seed + 0x9E3779B97F4A7C15L * (salt + 1);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public long next() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform in [0, bound). */
    public int below(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound " + bound);
        }
        return (int) Math.floorMod(next(), (long) bound);
    }

    /** Uniform in [low, high]. */
    public int between(int low, int high) {
        return low + below(high - low + 1);
    }

    public boolean chance(double p) {
        return (next() >>> 11) * 0x1.0p-53 < p;
    }

    public <T> T pick(List<T> from) {
        return from.get(below(from.size()));
    }

    @SafeVarargs
    public final <T> T pick(T... from) {
        return from[below(from.length)];
    }
}
