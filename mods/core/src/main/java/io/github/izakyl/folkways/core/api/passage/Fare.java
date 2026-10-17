package io.github.izakyl.folkways.core.api.passage;

import java.util.OptionalLong;

public interface Fare {

    OptionalLong arriveBy(long when);

    // The fare as caught by someone at the boarding place by when: a service that keeps a timetable holds to the run
    // they can catch then, so the ride knows which one it is waiting for.
    default Fare boardingAt(long when) {
        return this;
    }

    // Riding on from one fare into the next without getting off: the next is caught on arriving by the first.
    static Fare then(Fare first, Fare next) {
        return new Fare() {

            @Override
            public OptionalLong arriveBy(long when) {
                OptionalLong across = first.arriveBy(when);
                return across.isEmpty() ? across : next.arriveBy(across.getAsLong());
            }

            @Override
            public Fare boardingAt(long when) {
                OptionalLong across = first.arriveBy(when);
                return then(first.boardingAt(when), across.isEmpty() ? next : next.boardingAt(across.getAsLong()));
            }
        };
    }

    static Fare flat(int ticks) {
        int held = Math.max(1, ticks);
        return new Fare() {

            @Override
            public OptionalLong arriveBy(long when) {
                return OptionalLong.of(when + held);
            }
        };
    }
}
