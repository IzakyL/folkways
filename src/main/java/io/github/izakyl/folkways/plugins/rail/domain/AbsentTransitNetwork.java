package io.github.izakyl.folkways.plugins.rail.domain;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

final class AbsentTransitNetwork implements TransitNetwork {

    static final AbsentTransitNetwork INSTANCE = new AbsentTransitNetwork();

    private AbsentTransitNetwork() {
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public List<UUID> trains(MinecraftServer server) {
        return List.of();
    }

    @Override
    public boolean isServiceable(MinecraftServer server, UUID train) {
        return false;
    }

    @Override
    public List<SeatRef> conductorSeats(MinecraftServer server, UUID train, boolean forward) {
        return List.of();
    }

    @Override
    public boolean hasConductor(MinecraftServer server, UUID train, boolean forward) {
        return false;
    }

    @Override
    public List<SeatRef> passengerSeats(MinecraftServer server, UUID train) {
        return List.of();
    }

    @Override
    public Optional<WorldPos> stationPosition(MinecraftServer server, UUID train, String station) {
        return Optional.empty();
    }

    @Override
    public Optional<UUID> occupant(MinecraftServer server, SeatRef seat) {
        return Optional.empty();
    }

    @Override
    public Optional<WorldPos> seatPosition(MinecraftServer server, SeatRef seat) {
        return Optional.empty();
    }

    @Override
    public boolean board(MinecraftServer server, SeatRef seat, Entity rider) {
        return false;
    }

    @Override
    public void disembark(MinecraftServer server, SeatRef seat, Entity rider) {
    }

    @Override
    public Optional<String> currentStation(MinecraftServer server, UUID train) {
        return Optional.empty();
    }

    @Override
    public List<Stop> timetable(MinecraftServer server, UUID train) {
        return List.of();
    }

    @Override
    public Optional<Berth> berth(MinecraftServer server, UUID train, String station) {
        return Optional.empty();
    }

    @Override
    public List<String> scheduledStations(MinecraftServer server, UUID train) {
        return List.of();
    }
}
