package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.card.api.notice.Line;
import java.util.List;

public record ResidentDebug(List<Line> lines, List<DebugWork> route) {
    public ResidentDebug {
        lines = List.copyOf(lines);
        route = List.copyOf(route);
    }
}
