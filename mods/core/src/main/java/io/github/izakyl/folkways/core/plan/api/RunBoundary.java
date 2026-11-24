package io.github.izakyl.folkways.core.plan.api;

import io.github.izakyl.folkways.core.work.api.ReservationPool;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

/** A workshop offer whose execution belongs to independently submitted work. */
public interface RunBoundary extends Node {

    ReservationPool reservations();

    Optional<Ending> advance(ServerLevel level);

    void paused();

    @Override
    default Outcome commit(ServerLevel level, Worker worker) {
        throw new IllegalStateException("a run boundary has no worker");
    }
}
