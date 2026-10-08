package io.github.izakyl.folkways.plugins.rail.domain;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// How many times each train has pulled out of a station, counted as Create lets it go, loaded or not. A run of the
// timetable is known by the count it will leave at, so whether it has gone is seen, never reckoned from the clock.
public final class Departures {

    private static final Map<UUID, AtomicLong> LEFT = new ConcurrentHashMap<>();

    private Departures() {
    }

    public static void left(UUID train) {
        LEFT.computeIfAbsent(train, ignored -> new AtomicLong()).incrementAndGet();
    }

    public static long of(UUID train) {
        AtomicLong left = LEFT.get(train);
        return left == null ? 0L : left.get();
    }

    public static void forgetServer() {
        LEFT.clear();
    }
}
