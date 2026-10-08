package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import java.util.List;

public record Situation(Ways ways, Stock stock, List<Workshop> workshops, Stands stands) {

    public Situation {
        workshops = List.copyOf(workshops);
    }
}
