package io.github.izakyl.folkways.core.plan.internal;

import java.util.UUID;

public record Claim(Resource on, UUID owner, long amount) {
}
