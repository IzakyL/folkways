package io.github.izakyl.folkways.vanilla.create.elevator;

import io.github.izakyl.folkways.core.shell.create.elevator.ElevatorRef;
import io.github.izakyl.folkways.core.travel.api.Fare;
import java.util.OptionalLong;
import java.util.UUID;

// A pure planning snapshot. Looking for a route never queues a passenger or calls the lift.
record ElevatorFare(UUID colony, ElevatorRef lift, int from, int to, ElevatorForecast forecast) implements Fare {

    @Override
    public OptionalLong arriveBy(long when) {
        long arrival = forecast.arrive(from, to, when);
        return arrival == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(arrival);
    }
}
