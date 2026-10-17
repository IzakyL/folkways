package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.Stash;
import java.util.Set;
import java.util.UUID;

public record Asks(boolean survey, Set<Stash> looks, boolean hands, Set<UUID> handsOf, long wakeAt) {

    public static final Asks NONE = new Asks(false, Set.of(), false, Set.of(), Long.MAX_VALUE);

    public Asks {
        looks = Set.copyOf(looks);
        handsOf = Set.copyOf(handsOf);
    }
}
