package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;

// One place on a beat where a patroller stands watch; the ward it leaves reaches round it.
record Stop(WorldPos at, Stances stances) {
}
