package io.github.izakyl.folkways.plugins.rail.passage;

import io.github.izakyl.folkways.core.api.passage.Fare;
import java.util.OptionalLong;
import java.util.UUID;

// run is the departure count of the run the plan caught, once it has caught one.
record RailFare(UUID train, Timetable runs, String board, String alight, OptionalLong run) implements Fare {

    RailFare(UUID train, Timetable runs, String board, String alight) {
        this(train, runs, board, alight, OptionalLong.empty());
    }

    RailFare {
        if (board.equals(alight)) {
            throw new IllegalArgumentException("a hop from " + board + " to itself is not a crossing");
        }
    }

    @Override
    public OptionalLong arriveBy(long when) {
        return runs.arriveBy(board, alight, when);
    }

    @Override
    public Fare boardingAt(long when) {
        return new RailFare(train, runs, board, alight, runs.caughtAt(board, alight, when));
    }
}
