package io.github.izakyl.folkways.create.rail.domain;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import java.util.UUID;

record Cab(UUID train, SeatRef seat, boolean forward, WorldPos at, Stances stances) {
}
