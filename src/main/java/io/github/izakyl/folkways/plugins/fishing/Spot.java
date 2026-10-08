package io.github.izakyl.folkways.plugins.fishing;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;

record Spot(WorldPos water, Stances stances, int biteTicks) {
}
