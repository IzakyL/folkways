package io.github.izakyl.folkways.plugins.fishing;

import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;

record Spot(WorldPos water, Stances stances, int biteTicks) {
}
