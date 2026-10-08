package io.github.izakyl.folkways.plugins.rail.domain;

import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

final class ConductNode implements Node, Workload.Continuous {

    private final NodeSpec spec;
    private final Vocation trade;
    private final Cab cab;
    private final ConductorSeats seats;

    ConductNode(UUID id, Vocation trade, Cab cab, ConductorSeats seats) {
        this.trade = trade;
        this.cab = cab;
        this.seats = seats;
        this.spec = NodeSpec.of(id, RailContent.ID, cab.seat().site(cab.at()),
                Stances.WHEREVER, this)
            .effort(new WorkEffort.PerTick(1.0D / 20.0D))
            .vocation(trade)
            .gesture(WorkGesture.NONE)
            .doing(RailContent.DRIVING)
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public boolean ready(ServerLevel level) {
        return TransitNetworks.get().seatPosition(level.getServer(), cab.seat()).isPresent();
    }

    @Override
    public boolean holds(ServerLevel level, Worker who) {
        return TransitNetworks.get().occupant(level.getServer(), cab.seat())
            .filter(who.resident().id()::equals)
            .isPresent();
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(trade, 1)));
    }

    @Override
    public void released(ServerLevel level, Worker who, Ending how) {
        TransitNetworks.get().disembark(level.getServer(), cab.seat(), who.body());
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        seats.left(cab.seat());
    }
}
