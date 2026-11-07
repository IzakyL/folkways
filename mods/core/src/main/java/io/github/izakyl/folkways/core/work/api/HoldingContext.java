package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.resident.api.Body;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;

public record HoldingContext(ServerLevel level, Body body, Worker worker, ActionServices actions,
                             Settlement settlement, HoldingFactory holdings, boolean joining) {

    public HoldingContext(ServerLevel level, Body body, Worker worker, ActionServices actions,
            Settlement settlement, HoldingFactory holdings) {
        this(level, body, worker, actions, settlement, holdings, false);
    }

    // Private actions choose their own stance and share the outer node's input and output placement.
    public ActionHandle action(WorkAction action) {
        Placement plan = worker.placement();
        Placement placed = new Placement(Set.of(), plan.carrying(), plan.from(), plan.into(), plan.draws());
        return holdings.action(this, action, placed);
    }
}
