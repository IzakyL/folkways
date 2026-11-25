package io.github.izakyl.folkways.core.work.api;

import java.util.OptionalLong;

public sealed interface Readiness {
    Ready READY = Ready.INSTANCE;

    enum Ready implements Readiness {
        INSTANCE
    }

    record NotYet(OptionalLong ticks) implements Readiness {
        public NotYet {
            if (ticks.isPresent() && ticks.getAsLong() < 0) {
                throw new IllegalArgumentException("negative readiness estimate");
            }
        }

        private static final NotYet UNKNOWN = new NotYet(OptionalLong.empty());
    }

    static Readiness of(boolean ready) {
        return ready ? READY : notYet();
    }

    static NotYet notYet() {
        return NotYet.UNKNOWN;
    }

    static NotYet notYet(long ticks) {
        return new NotYet(OptionalLong.of(ticks));
    }

    default boolean isReady() {
        return this == READY;
    }
}
