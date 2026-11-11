package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.front.api.notice.Line;
import java.util.List;

public record SiteDebug(List<Line> lines, List<DebugWork> work) {
    public SiteDebug {
        lines = List.copyOf(lines);
        work = List.copyOf(work);
    }
}
