package io.github.izakyl.folkways.plugins.rail.passage;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

// departs[k] is the count of the train's departures that leaving calls[k] will be: whether a run has gone is read off
// that count as the train really leaves, never off the clock, which only says when a run is due.
record Timetable(List<String> calls, long[] arrive, long[] leave, long[] departs) {

    Timetable(List<String> calls, long[] arrive, long[] leave) {
        this(calls, arrive, leave, inOrder(calls.size()));
    }

    Timetable {
        calls = List.copyOf(calls);
        if (calls.size() != arrive.length || calls.size() != leave.length || calls.size() != departs.length) {
            throw new IllegalArgumentException(calls.size() + " stops with " + arrive.length + " arrivals, "
                + leave.length + " departures and " + departs.length + " departure counts is not a timetable");
        }
        for (int k = 0; k < arrive.length; k++) {
            if (leave[k] < arrive[k] || k > 0 && arrive[k] <= leave[k - 1]) {
                throw new IllegalArgumentException("stops must be in running order and no two at once; " + k
                    + " is not: " + describe(calls, arrive, leave, departs));
            }
        }
        arrive = arrive.clone();
        leave = leave.clone();
        departs = departs.clone();
    }

    private static long[] inOrder(int calls) {
        long[] departs = new long[calls];
        for (int k = 0; k < calls; k++) {
            departs[k] = k + 1L;
        }
        return departs;
    }

    OptionalLong arriveBy(String board, String alight, long when) {
        long best = Long.MAX_VALUE;
        for (int k = 0; k < arrive.length; k++) {
            if (!calls.get(k).equals(board) || when > leave[k]) {
                continue;
            }
            int lands = lands(k, alight);
            if (lands >= 0) {
                best = Math.min(best, arrive[lands]);
            }
        }
        return best == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(best);
    }

    // The run someone on the platform at board by when catches to alight, by the departure count it leaves at: the
    // first that has not left by then, so the one arriveBy reckons with.
    OptionalLong caughtAt(String board, String alight, long when) {
        for (int k = 0; k < arrive.length; k++) {
            if (calls.get(k).equals(board) && when <= leave[k] && lands(k, alight) >= 0) {
                return OptionalLong.of(departs[k]);
            }
        }
        return OptionalLong.empty();
    }

    // The departure that takes the last run from board to alight: once the train has left that many times, every run
    // this timetable offers between them has gone.
    OptionalLong lastDeparture(String board, String alight) {
        for (int k = arrive.length - 1; k >= 0; k--) {
            if (calls.get(k).equals(board) && lands(k, alight) >= 0) {
                return OptionalLong.of(departs[k]);
            }
        }
        return OptionalLong.empty();
    }

    private int lands(int board, String alight) {
        for (int k = board + 1; k < arrive.length; k++) {
            if (calls.get(k).equals(alight)) {
                return k;
            }
        }
        return -1;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Timetable(List<String> stops, long[] arrives, long[] leaves, long[] counts)
            && calls.equals(stops) && Arrays.equals(arrive, arrives) && Arrays.equals(leave, leaves)
            && Arrays.equals(departs, counts);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * calls.hashCode() + Arrays.hashCode(arrive)) + Arrays.hashCode(leave))
            + Arrays.hashCode(departs);
    }

    @Override
    public String toString() {
        return describe(calls, arrive, leave, departs);
    }

    private static String describe(List<String> calls, long[] arrive, long[] leave, long[] departs) {
        StringBuilder said = new StringBuilder("Timetable[");
        for (int k = 0; k < calls.size(); k++) {
            said.append(k > 0 ? ", " : "").append(calls.get(k)).append('@').append(arrive[k]).append('-')
                .append(leave[k]).append('#').append(departs[k]);
        }
        return said.append(']').toString();
    }
}
