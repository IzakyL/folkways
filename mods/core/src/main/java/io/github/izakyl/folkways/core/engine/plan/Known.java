package io.github.izakyl.folkways.core.engine.plan;

import java.util.List;

public record Known(Situation world, List<Crew.Hand> hands) {

    public Known {
        hands = List.copyOf(hands);
    }
}
