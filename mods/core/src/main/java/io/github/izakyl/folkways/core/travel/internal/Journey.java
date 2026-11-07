package io.github.izakyl.folkways.core.travel.internal;

import io.github.izakyl.folkways.core.world.api.WorldPos;
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
