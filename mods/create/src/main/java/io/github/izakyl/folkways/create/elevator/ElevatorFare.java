package io.github.izakyl.folkways.create.elevator;

import io.github.izakyl.folkways.core.api.passage.Fare;
import java.util.OptionalLong;
import java.util.UUID;

// A pure planning snapshot. Looking for a route never queues a passenger or calls the lift.
record ElevatorFare(UUID colony, ElevatorRef lift, int from, int to, long ticks) implements Fare {

    @Override
    public OptionalLong arriveBy(long when) {
        long duration = Math.max(1L, ticks);
        return when > Long.MAX_VALUE - duration ? OptionalLong.empty() : OptionalLong.of(when + duration);
    }
}
