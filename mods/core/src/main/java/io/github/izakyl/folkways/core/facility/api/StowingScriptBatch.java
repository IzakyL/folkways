package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.RunBoundary;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.ReservationPool;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Stances;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerLevel;

/** A standalone collection batch keeps its cargo booked until it reaches a colony store. */
final class StowingScriptBatch implements RunBoundary {

    private final Colony colony;
    private final DefinedFacilities facilities;
    private final FacilityDefinition facility;
    private final NodeSpec spec;
    private final ScriptBatch batch;
    private boolean started;
    private Ending result;

    StowingScriptBatch(Colony colony, DefinedFacilities facilities, FacilityDefinition facility, ScriptBatch batch) {
        this.colony = colony;
        this.facilities = facilities;
        this.facility = facility;
        spec = NodeSpec.of(UUID.randomUUID(), DefinedFacilities.ID, batch.spec().site(), Stances.WHEREVER,
            Workload.Once.of(0)).done();
        this.batch = new ScriptBatch(batch.spec(), batch.graph(), batch.holds(), ReservationPool.stowing());
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public ReservationPool reservations() {
        return batch.reservations();
    }

    @Override
    public Optional<Ending> advance(ServerLevel level) {
        if (!started && result == null) {
            started = true;
            BiConsumer<UUID, Ending> ended = (id, ending) -> {
                if (started) {
                    result = ending;
                }
            };
            if (!facilities.postRuns().start(level, facility, batch, false, false, ended)) {
                colony.submit(DefinedFacilities.ID, level.dimension(), Grown.of(batch), ended);
            }
        }
        return result != null && !reservations().hasCarried() ? Optional.of(Ending.DONE) : Optional.empty();
    }

    Ending result(Ending boundary) {
        return boundary instanceof Ending.Done && result != null ? result : boundary;
    }

    @Override
    public void paused() {
        if (started && result == null) {
            started = false;
            result = Ending.DROPPED;
            facilities.withdraw(batch.id());
        }
    }

    @Override
    public void ended(ServerLevel level, Ending ending) {
        paused();
    }
}
