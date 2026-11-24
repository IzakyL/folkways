package io.github.izakyl.folkways.core.shell.create.rail.passage;

import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.shell.create.rail.domain.Boarding;
import io.github.izakyl.folkways.core.shell.create.rail.domain.RailRefusal;
import io.github.izakyl.folkways.core.shell.create.rail.domain.SeatRef;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitNetwork;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitNetworks;
import io.github.izakyl.folkways.core.shell.create.transport.SeatedConveyance;
import io.github.izakyl.folkways.core.shell.create.transport.ContraptionSeats;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

final class RailConveyance extends SeatedConveyance<SeatRef> {

    private static final net.minecraft.resources.ResourceLocation BOARDING =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("folkways_core", "boarding");
    private static final net.minecraft.resources.ResourceLocation RIDING =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("folkways_core", "riding");

    private final RailLegs.Leg leg;
    private Long alightingDeparture;

    RailConveyance(RailLegs.Leg leg) {
        super(leg.boarding(), leg.landing(), Boarding::seatTicks,
            BOARDING, RIDING, RailRefusal.SEAT_GONE, RailRefusal.STRANDED);
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
            || !conducted(network, server, train)
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
    protected void driving(ServerLevel level, Mob body) {
        Ridership.driven(level, body, this::fallbackLanding);
    }

    @Override
    protected boolean rideWithdrawn(ServerLevel level) {
        TransitNetwork network = TransitNetworks.get();
        MinecraftServer server = level.getServer();
        UUID train = leg.train();
        if (!available(level) || !network.scheduledStations(server, train).contains(leg.alight())
                || network.fault(server, train).isPresent() || !conducted(network, server, train)) {
            return true;
        }
        long departed = network.departures(server, train);
        Optional<String> station = network.currentStation(server, train);
        if (alightingDeparture == null) {
            long boardingDeparture = station.filter(leg.board()::equals).isPresent() ? departed + 1 : departed;
            OptionalLong planned = leg.run().isPresent() ? leg.run()
                : leg.runs().lastDeparture(leg.board(), leg.alight());
            long stops = planned.isPresent() ? leg.runs().departuresUntil(planned.getAsLong(), leg.alight()) : 1L;
            alightingDeparture = boardingDeparture + stops;
        }
        return departed >= alightingDeparture || departed == alightingDeparture - 1
            && station.filter(stop -> !stop.equals(leg.alight())).isPresent();
    }

    private static boolean conducted(TransitNetwork network, MinecraftServer server, UUID train) {
        return network.hasConductor(server, train, true) || network.hasConductor(server, train, false);
    }

    @Override
    protected boolean betweenStops(ServerLevel level) {
        return TransitNetworks.get().currentStation(level.getServer(), leg.train()).isEmpty();
    }

    @Override
    protected void abandonSeat(ServerLevel level, Mob body) {
        ContraptionSeats.disembark(body);
        if (!body.isPassenger()) {
            Ridership.clearDestination(body);
        }
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
