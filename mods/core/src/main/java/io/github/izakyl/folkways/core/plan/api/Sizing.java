package io.github.izakyl.folkways.core.plan.api;

import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import java.util.List;
import java.util.Optional;

// How much work the plan grows at once: no more runs than one resident's pack carries the goods for. It is decided
// while an intent is refined into work, and work once grown is never resized.
public interface Sizing {

    // How many runs of work at `at`, each carrying what `perRun` marks as carried, one resident of trade `by` may be
    // given at once: `limit` at most, one at least.
    long runs(WorkSite at, List<Need> perRun, Optional<Vocation> by, long limit);

    Sizing UNBOUNDED = (at, perRun, by, limit) -> limit;
}
