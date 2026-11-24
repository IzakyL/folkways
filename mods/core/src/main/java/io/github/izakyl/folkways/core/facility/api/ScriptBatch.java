package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.work.api.ReservationPool;
import io.github.izakyl.folkways.core.plan.api.ScopedWork;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import java.util.List;

/** A script's validated action batch, folded into the plan as one request ending at its sink. */
public record ScriptBatch(NodeSpec spec, Grown graph, List<Hold> holds, ReservationPool reservations) implements ScopedWork {

    public ScriptBatch {
        holds = List.copyOf(holds);
    }
}
