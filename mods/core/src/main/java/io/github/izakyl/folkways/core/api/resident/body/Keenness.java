package io.github.izakyl.folkways.core.api.resident.body;

import java.util.Optional;

public enum Keenness {

    FIRST,
    SECOND,
    THIRD,
    FOURTH;

    public static final Keenness DEFAULT = THIRD;

    public int number() {
        return ordinal() + 1;
    }

    public static Optional<Keenness> ofNumber(int number) {
        return number < 1 || number > values().length
            ? Optional.empty()
            : Optional.of(values()[number - 1]);
    }
}
