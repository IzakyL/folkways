package io.github.izakyl.folkways.plugins.rail.domain;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

public interface TransitNetwork {

    static TransitNetwork absent() {
        return AbsentTransitNetwork.INSTANCE;
    }

    boolean isAvailable();

    List<UUID> trains(MinecraftServer server);

    default boolean exists(MinecraftServer server, UUID train) {
        return true;
    }

    boolean isServiceable(MinecraftServer server, UUID train);

    List<SeatRef> conductorSeats(MinecraftServer server, UUID train, boolean forward);

    boolean hasConductor(MinecraftServer server, UUID train, boolean forward);

    List<SeatRef> passengerSeats(MinecraftServer server, UUID train);

    Optional<WorldPos> stationPosition(MinecraftServer server, UUID train, String station);

    Optional<UUID> occupant(MinecraftServer server, SeatRef seat);

    Optional<WorldPos> seatPosition(MinecraftServer server, SeatRef seat);

    boolean board(MinecraftServer server, SeatRef seat, Entity rider);

    void disembark(MinecraftServer server, SeatRef seat, Entity rider);

    Optional<String> currentStation(MinecraftServer server, UUID train);

    record Stop(String station, int arrivesIn, int leavesIn) {
    }

    List<Stop> timetable(MinecraftServer server, UUID train);

    Optional<Berth> berth(MinecraftServer server, UUID train, String station);

    List<String> scheduledStations(MinecraftServer server, UUID train);

    // Why the train's schedule cannot be timetabled, if it cannot.
    default Optional<TimetableFault> fault(MinecraftServer server, UUID train) {
        return Optional.empty();
    }

    // How many times the train has left a station.
    default long departures(MinecraftServer server, UUID train) {
        return Departures.of(train);
    }

    // The departure count the timetable's first stop leaves at.
    default long nextDeparture(MinecraftServer server, UUID train) {
        return departures(server, train) + 1;
    }
}
