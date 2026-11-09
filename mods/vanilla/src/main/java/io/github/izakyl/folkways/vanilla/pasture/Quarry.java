package io.github.izakyl.folkways.vanilla.pasture;

import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import java.util.Optional;
import java.util.UUID;

record Quarry(UUID subject, WorkSite at, Stances stances, Optional<UUID> mate) {

    Quarry(UUID subject, WorkSite at, Stances stances) {
        this(subject, at, stances, Optional.empty());
    }
}
