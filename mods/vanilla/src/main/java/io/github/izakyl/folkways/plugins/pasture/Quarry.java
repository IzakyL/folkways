package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import java.util.Optional;
import java.util.UUID;

record Quarry(UUID subject, WorkSite at, Stances stances, Optional<UUID> mate) {

    Quarry(UUID subject, WorkSite at, Stances stances) {
        this(subject, at, stances, Optional.empty());
    }
}
