package io.github.izakyl.folkways.create.rail.passage;

import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.create.rail.domain.SeatRef;
import io.github.izakyl.folkways.create.rail.domain.TransitNetwork;
import io.github.izakyl.folkways.create.transport.ContraptionSeats;
import io.github.izakyl.folkways.create.transport.PassengerLanding;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class Ridership {

    private static final String TAG_DESTINATION = "folkways_create:ride_destination";

    // A rider whose trip has not stepped it for this long has no trip left. Generous, because a busy server may
    // defer colony work for some ticks, and setting a live passenger down mid-ride is worse than a short wait.
    private static final int DRIVE_GRACE_TICKS = 10 * 20;

    private static final Map<Entity, Drive> DRIVING = new WeakHashMap<>();

    private record Drive(long tick, BiFunction<ServerLevel, Mob, Optional<BlockPos>> fallback) {
    }

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
        DRIVING.remove(rider);
    }

    static void driven(ServerLevel level, Mob rider, BiFunction<ServerLevel, Mob, Optional<BlockPos>> fallback) {
        DRIVING.put(rider, new Drive(level.getGameTime(), fallback));
    }

    // Tick the carriage so this also covers passengers whose own AI is inactive while seated.
    public static void recoverPassengers(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof CarriageContraptionEntity carriage)
                || !(carriage.level() instanceof ServerLevel level)) {
            return;
        }
        for (Entity passenger : carriage.getPassengers()) {
            if (!(passenger instanceof Mob rider) || destinationOf(rider).isEmpty()) {
                continue;
            }
            if (carriage.getContraption() instanceof CarriageContraption contraption) {
                BlockPos seat = contraption.getSeatOf(rider.getUUID());
                if (seat != null && contraption.conductorSeats.containsKey(seat)) {
                    continue;
                }
            }
            long now = level.getGameTime();
            Drive drive = DRIVING.computeIfAbsent(rider, ignored -> new Drive(now, null));
            long undriven = now - drive.tick() - DRIVE_GRACE_TICKS;
            if (undriven <= 0) {
                continue;
            }
            Optional<BlockPos> landing = PassengerLanding.nearby(level, rider);
            if (landing.isEmpty() && undriven >= PassengerLanding.PLATFORM_FALLBACK_TICKS) {
                if (drive.fallback() != null) {
                    landing = drive.fallback().apply(level, rider);
                }
                landing = landing.or(() -> PassengerLanding.widerSearch(level, rider));
            }
            if (landing.isPresent()) {
                ContraptionSeats.disembark(rider);
                if (!rider.isPassenger()) {
                    PassengerLanding.place(rider, landing.get());
                    clearDestination(rider);
                }
            }
        }
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
