package io.github.izakyl.folkways.core.engine.travel;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.List;
import java.util.Set;

public record Journey(int walkTicks, int rideTicks, List<Leg> legs) {

    public Journey {
        legs = List.copyOf(legs);
    }

    public static Journey afoot(int ticks, Set<WorldPos> to) {
        return new Journey(ticks, 0, List.of(Leg.afoot(to)));
    }

    public int ticks() {
        return walkTicks + rideTicks;
    }
}
