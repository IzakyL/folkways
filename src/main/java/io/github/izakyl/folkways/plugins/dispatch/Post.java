package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import java.util.UUID;

record Post(UUID network, WorldPos desk, WorldPos seat, Stances stances) {
}
