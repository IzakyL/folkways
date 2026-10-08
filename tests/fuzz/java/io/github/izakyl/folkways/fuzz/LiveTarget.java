package io.github.izakyl.folkways.fuzz;

import java.util.List;
import java.util.Map;

/**
 * One subsystem fuzzed as it really runs: a case lays a colony and its work out on a plot, then the server ticks
 * and residents carry it out, while the case's disturbances land at their ticks. A case is plain data (maps,
 * lists, numbers, strings, booleans), so it goes out as JSON, sits in the corpus and comes back to be replayed or
 * shrunk; {@link #open} must take any case the shrinker makes of it (entries dropped from any list, counts
 * lowered), skipping references that no longer resolve.
 */
public interface LiveTarget {

    String name();

    Map<String, Object> draw(Rng rng);

    /** Ticks a case may run before it is judged as it stands. */
    int budget();

    /** How far the target's plot runs either side of its origin. */
    default int half() {
        return Plot.HALF;
    }

    /** How far the plot for this case runs either side of its origin: {@link #half()}, unless the case needs less. */
    default int half(Map<String, Object> kase) {
        return half();
    }

    /** Lays the case out on the plot, ready for its first tick. */
    Run open(Plot plot, Map<String, Object> kase);

    interface Run {

        /** Ticks this case may run before it is judged as it stands, if it knows better than {@link #budget()}. */
        default int budget() {
            return -1;
        }

        /**
         * Called between tick segments, {@code elapsed} ticks after the case opened: lands the disturbances that
         * are due and adds what has already gone wrong (a resident dead that nothing killed, say) to {@code into}.
         */
        void step(long elapsed, List<Violation> into);

        /** Whether there is nothing left to wait for: the work is done and has had time to tidy up. */
        boolean settled(long elapsed);

        /** What is wrong once the case has settled or run out of time. */
        List<Violation> judge(boolean settled, long elapsed);

        /** A few facts about how the run went, for the report. */
        Map<String, Object> summary();

        /** Takes everything the case laid and made back off the plot. */
        void close();
    }
}
