package io.github.izakyl.folkways.core.travel.internal;

import io.github.izakyl.folkways.core.travel.api.Hop;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import java.util.Set;

public record Leg(Optional<Hop> hop, Set<WorldPos> to, Optional<WorldPos> from, boolean passing) {

    public Leg {
        to = Set.copyOf(to);
    }

    public Leg(Optional<Hop> hop, Set<WorldPos> to) {
        this(hop, to, Optional.empty(), false);
    }

    public static Leg afoot(Set<WorldPos> to) {
        return new Leg(Optional.empty(), to);
    }

    public static Leg afoot(WorldPos from, Set<WorldPos> to) {
        return new Leg(Optional.empty(), to, Optional.of(from), false);
    }

    public static Leg through(WorldPos from, WorldPos to) {
        return new Leg(Optional.empty(), Set.of(to), Optional.of(from), true);
    }

    public static Leg aboard(Hop hop) {
        return new Leg(Optional.of(hop), hop.landing().cells());
    }
}
