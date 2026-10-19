package io.github.izakyl.folkways.core.engine.plan;

import java.util.UUID;

public record Claim(Resource on, UUID owner, long amount) {
}
