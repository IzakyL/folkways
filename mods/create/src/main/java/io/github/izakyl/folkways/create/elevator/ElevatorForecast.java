package io.github.izakyl.folkways.create.elevator;

import java.util.List;

/** Immutable planning data: looking ahead never calls the lift or reserves a passenger's place. */
record ElevatorForecast(long clock, double position, double speed, long window, int capacity, List<Trip> trips) {
    record Trip(int from, int to, int load, boolean aboard, boolean accepting) { }
    ElevatorForecast { trips = List.copyOf(trips); }

    long arrive(int from, int to, long when) {
        if (capacity <= 0) { return Long.MAX_VALUE; }
        long time = clock;
        double y = position;
        for (Trip trip : trips) {
            long depart = trip.aboard() && !trip.accepting() ? time : add(time, travel(y, trip.from()), window);
            if (trip.accepting() && trip.load() < capacity && trip.from() == from && trip.to() == to && when <= depart) {
                return add(depart, travel(trip.from(), trip.to()), window);
            }
            time = add(depart, travel(trip.aboard() && !trip.accepting() ? y : trip.from(), trip.to()), window);
            y = trip.to();
        }
        return add(Math.max(time, when), travel(y, from), window, travel(from, to), window);
    }

    private long travel(double from, int to) {
        return (long) Math.ceil(Math.abs(to - from) / Math.max(0.01D, speed)) + 20;
    }

    private static long add(long value, long... durations) {
        for (long duration : durations) {
            if (value > Long.MAX_VALUE - duration) { return Long.MAX_VALUE; }
            value += duration;
        }
        return value;
    }
}
