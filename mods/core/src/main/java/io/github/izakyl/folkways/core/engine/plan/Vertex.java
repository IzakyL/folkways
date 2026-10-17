package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import java.util.UUID;

public record Vertex(UUID id, Node node, Placement plan) {
}
