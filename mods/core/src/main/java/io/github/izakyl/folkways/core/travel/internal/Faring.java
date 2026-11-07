package io.github.izakyl.folkways.core.travel.internal;

public sealed interface Faring {

    record Yes(Journey by) implements Faring {
    }

    record No() implements Faring {
    }

    record Later() implements Faring {
    }

    Faring NO = new No();

    Faring LATER = new Later();

    static Faring yes(Journey by) {
        return new Yes(by);
    }
}
