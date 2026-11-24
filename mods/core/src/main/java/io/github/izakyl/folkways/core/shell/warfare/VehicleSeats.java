package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.facility.api.Posts;
import io.github.izakyl.folkways.core.facility.api.control.PostKey;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;

/** Stable (hull UUID, seat index, resident UUID) leases, independent of a hull's changing position. */
public final class VehicleSeats {
    public static final ResourceLocation POST_SOURCE = ResourceLocation.parse("folkways_vanilla:vehicle_control");
    public static final String CREW = "folkways_core:vehicle_crew";
    public static final String SEAT = "folkways_core:vehicle_seat";

    private VehicleSeats() {
    }

    public static CompoundTag crew(VehicleEntity vehicle) {
        return vehicle.getPersistentData().getCompound(CREW);
    }

    public static Optional<UUID> owner(VehicleEntity vehicle) {
        CompoundTag crew = crew(vehicle);
        return crew.hasUUID("owner") ? Optional.of(crew.getUUID("owner")) : Optional.empty();
    }

    public static Optional<UUID> assigned(VehicleEntity vehicle, int seat) {
        CompoundTag crew = crew(vehicle);
        String key = "seat" + seat;
        return crew.hasUUID(key) ? Optional.of(crew.getUUID(key)) : Optional.empty();
    }

    public static PostKey postKey(VehicleEntity vehicle, int seat) {
        return new PostKey(POST_SOURCE, vehicle.getUUID(), Integer.toString(seat));
    }

    public static Optional<Mob> occupant(VehicleEntity vehicle, int seat) {
        if (!(vehicle.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        return owner(vehicle).flatMap(id -> Colonies.of(level.getServer(), id)).flatMap(Posts::of)
            .flatMap(posts -> posts.occupant(level, postKey(vehicle, seat), new Stances.Seat(vehicle.getUUID(), seat)))
            .map(Worker::body);
    }

    public static boolean holds(VehicleEntity vehicle, Body body, int seat) {
        return body.mob().isAlive() && !vehicle.isWreck() && !vehicle.getLocked()
            && body.mob().getVehicle() == vehicle && vehicle.getSeatIndex(body.mob()) == seat
            && assigned(vehicle, seat).filter(body.id()::equals).isPresent()
            && owner(vehicle).filter(id -> body.colonyId().filter(id::equals).isPresent()).isPresent();
    }

    public static boolean holds(VehicleEntity vehicle, LivingEntity entity) {
        return Bodies.of(entity).filter(body -> holds(vehicle, body, vehicle.getSeatIndex(entity))).isPresent();
    }

    public static boolean matches(LivingEntity body, VehicleEntity vehicle, int seat) {
        CompoundTag loan = body.getPersistentData().getCompound(SEAT);
        return loan.hasUUID("vehicle") && loan.getUUID("vehicle").equals(vehicle.getUUID())
            && loan.getInt("seat") == seat;
    }

    public static void clear(VehicleEntity vehicle) {
        for (int seat = 0; seat < vehicle.getMaxPassengers(); seat++) {
            release(vehicle, seat);
        }
        vehicle.getPersistentData().remove(CREW);
    }

    public static void assign(VehicleEntity vehicle, Body body, int seat) {
        CompoundTag previous = body.mob().getPersistentData().getCompound(SEAT);
        if (previous.hasUUID("vehicle") && !previous.getUUID("vehicle").equals(vehicle.getUUID())
            && body.mob().level() instanceof ServerLevel level
            && level.getEntity(previous.getUUID("vehicle")) instanceof VehicleEntity old
            && assigned(old, previous.getInt("seat")).filter(body.id()::equals).isPresent()) {
            release(old, previous.getInt("seat"));
        }
        CompoundTag crew = crew(vehicle);
        for (String key : Set.copyOf(crew.getAllKeys())) {
            if (key.startsWith("seat") && crew.hasUUID(key) && crew.getUUID(key).equals(body.id())) {
                crew.remove(key);
            }
        }
        crew.putUUID("owner", body.colonyId().orElseThrow());
        crew.putUUID("seat" + seat, body.id());
        vehicle.getPersistentData().put(CREW, crew);
        WarfareAirfields.get().home(vehicle);
        CompoundTag loan = new CompoundTag();
        loan.putUUID("vehicle", vehicle.getUUID());
        loan.putInt("seat", seat);
        loan.putLong("assignedAt", vehicle.level().getGameTime());
        body.mob().getPersistentData().put(SEAT, loan);
    }

    public static void release(VehicleEntity vehicle, int seat) {
        VehicleGuns.of(vehicle).clearAim();
        CompoundTag crew = crew(vehicle);
        assigned(vehicle, seat).map(id -> ((ServerLevel) vehicle.level()).getEntity(id))
            .filter(LivingEntity.class::isInstance).map(LivingEntity.class::cast)
            .filter(entity -> matches(entity, vehicle, seat))
            .ifPresent(entity -> entity.getPersistentData().remove(SEAT));
        crew.remove("seat" + seat);
        if (crew.isEmpty()) {
            vehicle.getPersistentData().remove(CREW);
        } else {
            vehicle.getPersistentData().put(CREW, crew);
        }
    }
}
