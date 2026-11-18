package io.github.izakyl.folkways.create.rail.domain;

import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import java.util.UUID;

record Cab(UUID train, SeatRef seat, boolean forward, WorldPos at, Stances stances, Optional<UUID> driver) {

    Cab(UUID train, SeatRef seat, boolean forward, WorldPos at, Stances stances) {
        this(train, seat, forward, at, stances, Optional.empty());
    }
}
