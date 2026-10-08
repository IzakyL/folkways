package io.github.izakyl.folkways.core.engine.plan;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Schedule(Map<UUID, Tour> tours, List<Unassigned> unassigned) {

    public Schedule {
        tours = Map.copyOf(tours);
        unassigned = List.copyOf(unassigned);
    }

    public static final Schedule EMPTY = new Schedule(Map.of(), List.of());
}
