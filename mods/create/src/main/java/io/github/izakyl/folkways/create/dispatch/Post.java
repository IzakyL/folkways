package io.github.izakyl.folkways.create.dispatch;

import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.UUID;

record Post(UUID network, WorldPos desk, WorldPos seat, Stances stances) {
}
