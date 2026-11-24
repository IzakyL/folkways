package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.base.persist.Required;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * How workers take a {@link Stances.Seat}: where a seat is boarded from, whether a body is in it, and sitting down.
 * Adapters answer for the entities they know (a vehicle's numbered seats); any other entity is sat on as an ordinary
 * passenger, its seats numbered by passenger order.
 *
 * <p>A seat taken here belongs to the work whose stance it is: the body stands up when that work is let go, when it
 * dies or leaves the level, and a seat still marked after a reload is let go, never resumed. A body that left the seat
 * for another ride keeps that ride. All of it runs on the server thread.
 */
public final class Seating {

    /** How a kind of entity is sat in. */
    public interface Adapter {

        /** Whether this adapter seats bodies in this entity. */
        boolean seats(Entity seat);

        /** The cells a body boards the seat from, in the realm it stands in; empty when it cannot board now. */
        Set<WorldPos> footings(ServerLevel level, Entity seat, int index, Mob body);

        /** Whether the body is in this seat. */
        boolean in(Entity seat, int index, Mob body);

        /** Sits a body standing at a footing down in this seat; false, leaving it standing, when it cannot now. */
        boolean sit(Entity seat, int index, Mob body);

        /** Whether this ride belongs to the work that uses it. */
        default boolean temporary(Entity seat, int index, Mob body) {
            return true;
        }
    }

    public static final int SPECIFIC = 1000;

    private static final String MARKER = "folkways_core:seat_stance";
    private static final Adapter PASSENGER = new Passenger();

    private record Entry(ResourceLocation owner, int priority, Adapter adapter) {
    }

    private record Held(UUID token, UUID entity) {
    }

    private static volatile List<Entry> adapters = List.of();
    private static final Map<UUID, Held> HELD = new HashMap<>();

    private Seating() {
    }

    public static synchronized void register(ResourceLocation owner, int priority, Adapter adapter) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adapter, "adapter");
        if (adapters.stream().anyMatch(entry -> entry.owner().equals(owner))) {
            throw new IllegalStateException(owner + " already seats");
        }
        List<Entry> next = new ArrayList<>(adapters);
        next.add(new Entry(owner, priority, adapter));
        next.sort(Comparator.comparingInt(Entry::priority).reversed());
        adapters = List.copyOf(next);
    }

    private static Adapter adapter(Entity seat) {
        for (Entry entry : adapters) {
            if (entry.adapter().seats(seat)) {
                return entry.adapter();
            }
        }
        return PASSENGER;
    }

    private static Optional<Entity> entity(ServerLevel level, Stances.Seat seat) {
        Entity entity = level.getEntity(seat.entity());
        return entity == null || !entity.isAlive() || entity.isRemoved() ? Optional.empty() : Optional.of(entity);
    }

    /** The seat's location, for work done while seated. */
    public static Optional<WorldPos> position(ServerLevel level, Stances.Seat seat) {
        return entity(level, seat).map(WorldSpaces::at);
    }

    /** Where the body boards the seat from; empty when the seat is gone or cannot be boarded now. */
    public static Set<WorldPos> footings(ServerLevel level, Stances.Seat seat, Mob body) {
        return entity(level, seat).map(entity -> adapter(entity).footings(level, entity, seat.seat(), body))
            .map(Set::copyOf).orElse(Set.of());
    }

    /** Whether the body is in the seat, however it got there. */
    public static boolean in(Mob body, Stances.Seat seat) {
        Entity vehicle = body.getVehicle();
        return vehicle != null && vehicle.getUUID().equals(seat.entity()) && adapter(vehicle).in(vehicle, seat.seat(), body);
    }

    /**
     * Sits the body down in the seat, from where it stands. The seat is the engine's until {@link #rise}: it is marked
     * on the body so that a reload lets it go.
     */
    public static boolean sit(ServerLevel level, Mob body, Stances.Seat seat) {
        if (!level.getServer().isSameThread() || body.level() != level || !body.isAlive()) {
            return false;
        }
        if (in(body, seat)) {
            if (adapter(body.getVehicle()).temporary(body.getVehicle(), seat.seat(), body)) {
                hold(body, seat);
            }
            return true;
        }
        Optional<Entity> entity = entity(level, seat);
        if (entity.isEmpty() || body.isPassenger() && body.getVehicle() != entity.get()) {
            return false;
        }
        Adapter seating = adapter(entity.get());
        if (!seating.sit(entity.get(), seat.seat(), body) || !in(body, seat)) {
            return false;
        }
        if (seating.temporary(entity.get(), seat.seat(), body)) {
            hold(body, seat);
        }
        return true;
    }

    private static void hold(Mob body, Stances.Seat seat) {
        Held held = HELD.get(body.getUUID());
        if (held != null && held.entity().equals(seat.entity())) {
            return;
        }
        held = new Held(UUID.randomUUID(), seat.entity());
        HELD.put(body.getUUID(), held);
        CompoundTag marker = new CompoundTag();
        marker.putUUID("token", held.token());
        marker.putUUID("entity", held.entity());
        body.getPersistentData().put(MARKER, marker);
    }

    /** Whether the body sits in this seat, seated there by the engine. */
    public static boolean holds(Mob body, Stances.Seat seat) {
        Held held = HELD.get(body.getUUID());
        return held != null && held.entity().equals(seat.entity()) && in(body, seat);
    }

    /** Whether the body sits in any seat the engine sat it in. */
    public static boolean holds(Mob body) {
        Held held = HELD.get(body.getUUID());
        return held != null && body.getVehicle() != null && body.getVehicle().getUUID().equals(held.entity());
    }

    /** Stands the body up from a seat the engine sat it in. A ride it took since is left alone. */
    public static void rise(Mob body) {
        Held held = HELD.remove(body.getUUID());
        if (held == null) {
            return;
        }
        if (body.getVehicle() != null && body.getVehicle().getUUID().equals(held.entity())) {
            body.stopRiding();
        }
        CompoundTag marker = body.getPersistentData().getCompound(MARKER);
        if (marker.hasUUID("token") && marker.getUUID("token").equals(held.token())) {
            body.getPersistentData().remove(MARKER);
        }
    }

    /** As {@link #rise(Mob)}, only from this seat. */
    public static void rise(Mob body, Stances.Seat seat) {
        Held held = HELD.get(body.getUUID());
        if (held != null && held.entity().equals(seat.entity())) {
            rise(body);
        }
    }

    /** Core only. */
    public static void install() {
        NeoForge.EVENT_BUS.addListener(Seating::restored);
        NeoForge.EVENT_BUS.addListener(Seating::leaving);
        NeoForge.EVENT_BUS.addListener(Seating::dying);
    }

    // Runs after passengers are restored: a seat saved under a stance never resumes.
    private static void restored(EntityTickEvent.Post event) {
        Entity body = event.getEntity();
        if (body.level().isClientSide() || !body.getPersistentData().contains(MARKER)) {
            return;
        }
        CompoundTag marker = Required.compound(body.getPersistentData(), MARKER);
        UUID entity = Required.uuid(marker, "entity");
        Held held = HELD.get(body.getUUID());
        if (held != null && marker.hasUUID("token") && held.token().equals(marker.getUUID("token"))) {
            return;
        }
        if (body.getVehicle() != null && body.getVehicle().getUUID().equals(entity)) {
            body.stopRiding();
        }
        body.getPersistentData().remove(MARKER);
    }

    private static void leaving(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity left = event.getEntity();
        if (left instanceof Mob body && HELD.containsKey(body.getUUID())) {
            rise(body);
        }
        if (HELD.values().stream().anyMatch(held -> held.entity().equals(left.getUUID()))) {
            HELD.entrySet().removeIf(entry -> entry.getValue().entity().equals(left.getUUID()));
        }
    }

    private static void dying(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide() && event.getEntity() instanceof Mob body) {
            rise(body);
        }
    }

    // Any entity a body can ride: boarded from beside it, its seats in passenger order.
    private static final class Passenger implements Adapter {

        @Override
        public boolean seats(Entity seat) {
            return true;
        }

        @Override
        public Set<WorldPos> footings(ServerLevel level, Entity seat, int index, Mob body) {
            return Reach.workableCells(level, seat.blockPosition()).stream()
                .map(cell -> WorldPos.of(level, cell)).collect(Collectors.toSet());
        }

        @Override
        public boolean in(Entity seat, int index, Mob body) {
            return body.getVehicle() == seat && seat.getPassengers().indexOf(body) == index;
        }

        @Override
        public boolean sit(Entity seat, int index, Mob body) {
            if (seat.getPassengers().size() != index || !body.startRiding(seat)) {
                return false;
            }
            if (!in(seat, index, body)) {
                body.stopRiding();
                return false;
            }
            return true;
        }
    }
}
