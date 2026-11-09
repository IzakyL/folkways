package io.github.izakyl.folkways.vanilla.fishing;

import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;

record Spot(WorldPos water, Stances stances, int biteTicks) {
}
