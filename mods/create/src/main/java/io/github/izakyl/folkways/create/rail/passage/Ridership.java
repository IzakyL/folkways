package io.github.izakyl.folkways.create.rail.passage;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.create.rail.domain.SeatRef;
import io.github.izakyl.folkways.create.rail.domain.TransitNetwork;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

public final class Ridership {

    private static final String TAG_DESTINATION = "folkways:ride_destination";

    private Ridership() {
    }

    public static List<SeatRef> allSeats(MinecraftServer server, TransitNetwork network, UUID train) {
        List<SeatRef> seats = new ArrayList<>(network.passengerSeats(server, train));
        for (boolean forward : new boolean[] {true, false}) {
            for (SeatRef seat : network.conductorSeats(server, train, forward)) {
                if (!seats.contains(seat)) {
                    seats.add(seat);
                }
            }
        }
        return seats;
    }

    public static Optional<String> destinationOf(Entity rider) {
        CompoundTag tag = rider.getPersistentData();
        return tag.contains(TAG_DESTINATION, Tag.TAG_STRING)
            ? Optional.of(tag.getString(TAG_DESTINATION))
            : Optional.empty();
    }

    public static void setDestination(Entity rider, String station) {
        rider.getPersistentData().putString(TAG_DESTINATION, station);
    }

    public static void clearDestination(Entity rider) {
        rider.getPersistentData().remove(TAG_DESTINATION);
    }

    // The carriage a resident rides is stowing it out of the world, to put it back when the carriage is loaded again.
    public static void carriedOff(Entity rider) {
        if (!(rider.level() instanceof ServerLevel level)) {
            return;
        }
        Bodies.of(rider).ifPresent(body -> body.colonyId()
            .flatMap(id -> Colonies.of(level.getServer(), id))
            .ifPresent(colony -> colony.carriedOff(body.id())));
    }
}
