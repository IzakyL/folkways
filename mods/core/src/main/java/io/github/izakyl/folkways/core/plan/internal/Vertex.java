package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Placement;
import java.util.UUID;

public record Vertex(UUID id, Node node, Placement plan) {
}
