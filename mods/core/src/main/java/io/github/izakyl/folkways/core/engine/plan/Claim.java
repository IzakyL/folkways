package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.work.Placement;
import java.util.Optional;
import java.util.UUID;

public record Claim(Resource on, UUID owner, long amount, Optional<Placement.Input> input) {
    public Claim(Resource on, UUID owner, long amount) {
        this(on, owner, amount, Optional.empty());
    }
}
