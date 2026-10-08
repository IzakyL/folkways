package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * How long a network takes to land an order at a port: measured once parcels have arrived there, reckoned from the
 * network's layout before that. A route whose order never turns up is shut for a while, longer each time it fails again.
 */
final class Eta {

    static final int BLIND_GUESS = 20 * 15;

    // Past this many times its expected ticks, an order still not at its port is taken to be lost.
    static final int OVERDUE_FACTOR = 3;

    static final int OVERDUE_FLOOR = 20 * 60;

    static final long SHUT_TICKS = 20 * 60 * 2;

    static final int MOST_DOUBLINGS = 4;

    private record Route(WorldPos port, UUID network) {
    }

    private final Map<Route, Integer> measured = new LinkedHashMap<>();

    private final Map<Route, Integer> strikes = new LinkedHashMap<>();

    private final Map<Route, Long> shutUntil = new LinkedHashMap<>();

    synchronized void took(WorldPos port, UUID network, int ticks) {
        Route route = new Route(port, network);
        Integer before = measured.get(route);
        measured.put(route, before == null ? ticks : (before * 3 + ticks) / 4);
        strikes.remove(route);
        shutUntil.remove(route);
    }

    synchronized void lost(WorldPos port, UUID network, long now) {
        Route route = new Route(port, network);
        int times = strikes.merge(route, 1, Integer::sum);
        shutUntil.put(route, now + (SHUT_TICKS << Math.min(times - 1, MOST_DOUBLINGS)));
    }

    synchronized boolean shut(WorldPos port, UUID network, long now) {
        Long until = shutUntil.get(new Route(port, network));
        return until != null && now < until;
    }

    synchronized int estimate(WorldPos port, UUID network, OptionalInt reckoned) {
        Integer seen = measured.get(new Route(port, network));
        if (seen != null) {
            return seen;
        }
        return reckoned.orElse(BLIND_GUESS);
    }

    static long overdueAfter(int expected) {
        return Math.max(OVERDUE_FLOOR, (long) expected * OVERDUE_FACTOR);
    }
}
