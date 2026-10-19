package io.github.izakyl.folkways.create.rail.domain;

import java.util.List;
import java.util.Optional;

public record TrainCrew(List<String> drivers, int seatsTaken, int seatsTotal, Optional<TimetableFault> fault) {

    public TrainCrew(List<String> drivers, int seatsTaken, int seatsTotal) {
        this(drivers, seatsTaken, seatsTotal, Optional.empty());
    }

    public TrainCrew {
        drivers = List.copyOf(drivers);
        if (seatsTaken < 0 || seatsTotal < 0 || seatsTaken > seatsTotal) {
            throw new IllegalArgumentException(seatsTaken + " of " + seatsTotal + " seats");
        }
    }
}
