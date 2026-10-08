package io.github.izakyl.folkways.core.engine.plan;

import java.util.List;
import java.util.UUID;

public record Tour(UUID resident, List<UUID> nodes) {

    public Tour {
        nodes = List.copyOf(nodes);
    }
}
