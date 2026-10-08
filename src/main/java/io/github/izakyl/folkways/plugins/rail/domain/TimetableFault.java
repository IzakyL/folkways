package io.github.izakyl.folkways.plugins.rail.domain;

import java.util.Optional;

// Why a train's schedule cannot be told ahead of time, so the colony does not ride it: which rule it breaks (a
// translation key) and the stop, or schedule entry, that breaks it.
public record TimetableFault(String key, String stop) {

    public static final String UNNAMED = "folkways.train_map.unfit.unnamed";
    public static final String PATTERN = "folkways.train_map.unfit.pattern";
    public static final String NO_STATION = "folkways.train_map.unfit.no_station";
    public static final String SHARED_NAME = "folkways.train_map.unfit.shared_name";
    public static final String NOT_TIMED = "folkways.train_map.unfit.not_timed";
    public static final String NOT_A_STOP = "folkways.train_map.unfit.not_a_stop";
    public static final String NO_STOPS = "folkways.train_map.unfit.no_stops";

    public static Optional<TimetableFault> of(String key, String stop) {
        return Optional.of(new TimetableFault(key, stop));
    }
}
