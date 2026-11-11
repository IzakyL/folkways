package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.NodeState;
import io.github.izakyl.folkways.core.work.api.Placement;
import java.util.Optional;
import java.util.UUID;

// Copies only the server's applied plan and execution, never the solver's working graph.
public record DebugWork(NodeSpec spec, Placement placement, NodeState phase, Optional<UUID> resident,
                        int index, boolean declined) {
}
