package io.github.izakyl.folkways.create.rail.passage;

import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.create.rail.domain.Boarding;
import io.github.izakyl.folkways.create.rail.domain.RailContent;
import io.github.izakyl.folkways.create.rail.domain.RailRefusal;
import io.github.izakyl.folkways.create.rail.domain.SeatRef;
import io.github.izakyl.folkways.create.rail.domain.TransitNetwork;
import io.github.izakyl.folkways.create.rail.domain.TransitNetworks;
import io.github.izakyl.folkways.create.transport.SeatedConveyance;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

final class RailConveyance extends SeatedConveyance<SeatRef> {

    private final RailLegs.Leg leg;

    RailConveyance(RailLegs.Leg leg) {
        super(leg.boarding(), leg.landing(), Boarding::seatTicks,
            RailContent.BOARDING, RailContent.RIDING, RailRefusal.SEAT_GONE);
        this.leg = leg;
    }

    @Override
    protected boolean available(ServerLevel level) {
        TransitNetwork network = TransitNetworks.get();
        return network.isAvailable() && network.isServiceable(level.getServer(), leg.train());
    }

    @Override
    protected Optional<RefusalKind> boardingFailure(ServerLevel level) {
        TransitNetwork network = TransitNetworks.get();
        MinecraftServer server = level.getServer();
        UUID train = leg.train();
        List<String> stops = network.scheduledStations(server, train);
        // A withdrawn schedule or a missing driver leaves no service to wait for.
        boolean withdrawn = network.fault(server, train).isPresent()
            || !network.hasConductor(server, train, true) && !network.hasConductor(server, train, false)
            || !stops.contains(leg.board()) || !stops.contains(leg.alight());
        return withdrawn ? Optional.of(RailRefusal.LINE_WITHDRAWN) : Optional.empty();
    }

    @Override
    protected Going awaitVehicle(ServerLevel level) {
        TransitNetwork network = TransitNetworks.get();
        MinecraftServer server = level.getServer();
        if (network.currentStation(server, leg.train()).filter(leg.board()::equals).isPresent()) {
            return Going.ARRIVED;
        }
        // Judge a missed run by actual departures, rather than the timetable's predicted clock.
        OptionalLong run = leg.run().isPresent() ? leg.run() : leg.runs().lastDeparture(leg.board(), leg.alight());
        return run.isEmpty() || network.departures(server, leg.train()) >= run.getAsLong()
            ? new Going.Failed(RailRefusal.RUN_MISSED) : Going.UNDERWAY;
    }

    @Override
    protected Optional<SeatRef> freeSeat(ServerLevel level) {
        TransitNetwork network = TransitNetworks.get();
        MinecraftServer server = level.getServer();
        return network.passengerSeats(server, leg.train()).stream()
            .filter(seat -> network.occupant(server, seat).isEmpty()).findFirst();
    }

    @Override
    protected boolean occupied(ServerLevel level, SeatRef seat) {
        return TransitNetworks.get().occupant(level.getServer(), seat).isPresent();
    }

    @Override
    protected boolean board(ServerLevel level, SeatRef seat, Mob body) {
        if (!TransitNetworks.get().board(level.getServer(), seat, body)) {
            return false;
        }
        Ridership.setDestination(body, leg.alight());
        return true;
    }

    @Override
    protected boolean atDestination(ServerLevel level) {
        return TransitNetworks.get().currentStation(level.getServer(), leg.train())
            .filter(leg.alight()::equals).isPresent();
    }

    @Override
    protected Optional<SeatRef> seatOf(ServerLevel level, Mob body) {
        TransitNetwork network = TransitNetworks.get();
        MinecraftServer server = level.getServer();
        UUID id = body.getUUID();
        return Ridership.allSeats(server, network, leg.train()).stream()
            .filter(seat -> network.occupant(server, seat).filter(id::equals).isPresent()).findFirst();
    }

    @Override
    protected void disembark(ServerLevel level, SeatRef seat, Mob body) {
        TransitNetworks.get().disembark(level.getServer(), seat, body);
        Ridership.clearDestination(body);
    }

    @Override
    protected boolean canRelease(ServerLevel level) {
        return TransitNetworks.get().isAvailable();
    }
}
