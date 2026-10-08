package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import java.util.UUID;

record Quarry(UUID subject, WorkSite at, Stances stances) {
}
