package io.github.izakyl.folkways.create.elevator;

import io.github.izakyl.folkways.core.api.passage.Fare;
import java.util.OptionalLong;
import java.util.UUID;

// A pure planning snapshot. Looking for a route never queues a passenger or calls the lift.
record ElevatorFare(UUID colony, ElevatorRef lift, int from, int to, long ticks, ElevatorForecast forecast) implements Fare {

    ElevatorFare(UUID colony, ElevatorRef lift, int from, int to, long ticks) {
        this(colony, lift, from, to, ticks, null);
    }

    ElevatorFare(UUID colony, ElevatorRef lift, int from, int to, ElevatorForecast forecast) {
        this(colony, lift, from, to, 0, forecast);
    }

    @Override
    public OptionalLong arriveBy(long when) {
        if (forecast != null) {
            long arrival = forecast.arrive(from, to, when);
            return arrival == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(arrival);
        }
        long duration = Math.max(1L, ticks);
        return when > Long.MAX_VALUE - duration ? OptionalLong.empty() : OptionalLong.of(when + duration);
    }
}
