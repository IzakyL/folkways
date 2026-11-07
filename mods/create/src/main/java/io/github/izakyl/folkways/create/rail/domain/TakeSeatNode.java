package io.github.izakyl.folkways.create.rail.domain;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.api.Xp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

final class TakeSeatNode implements Node {

    private final NodeSpec spec;
    private final Vocation trade;
    private final Cab cab;
    private final ConductorSeats seats;

    TakeSeatNode(UUID id, Vocation trade, Cab cab, int seatTicks, ConductorSeats seats) {
        this.trade = trade;
        this.cab = cab;
        this.seats = seats;
        this.spec = NodeSpec.of(id, RailContent.ID, cab.seat().site(cab.at()),
                cab.stances(), Workload.Once.of(seatTicks, RailContent::haste))
            .vocation(trade)
            .gesture(WorkGesture.NONE)
            .doing(RailContent.TAKING_SEAT)
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public Optional<RefusalKind> planned(ServerLevel level) {
        seats.boarding(cab);
        return Optional.empty();
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        if (how instanceof Ending.Done) {
            seats.seated(cab.seat());
        } else {
            seats.left(cab.seat());
        }
    }

    @Override
    public boolean ready(ServerLevel level) {
        TransitNetwork network = TransitNetworks.get();
        return network.seatPosition(level.getServer(), cab.seat()).isPresent()
            && network.occupant(level.getServer(), cab.seat()).isEmpty();
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        TransitNetwork network = TransitNetworks.get();
        SeatRef seat = cab.seat();
        if (network.seatPosition(level.getServer(), seat).isEmpty()) {
            return Outcome.failed(RailRefusal.SEAT_GONE);
        }
        if (network.occupant(level.getServer(), seat).isPresent()
            || !network.board(level.getServer(), seat, who.body())) {
            return Outcome.failed(RailRefusal.SEAT_TAKEN);
        }
        return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(trade, 1)));
    }
}
