package io.github.izakyl.folkways.plugins.combat;

import io.github.izakyl.folkways.base.persist.Required;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.front.api.ZoneView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Fixed weapons supplied by optional Vanilla integrations. Discovery never acquires an operator. */
public final class WeaponStations {

    public enum Shot {

        FIRED,
        COOLDOWN,
        NO_AMMO,
        BLOCKED,
        STALE,
        UNAVAILABLE,
        REFUSED
    }

    /** Finders return stable station instances while the same fixed weapon exists. */
    public interface Station {

        WorldPos at();

        default Optional<UUID> entity() {
            return Optional.empty();
        }

        double range();

        default Optional<Vec3> origin(ServerLevel level) {
            return WorldSpaces.world(level, at().above()).map(point -> point.add(0, .5, 0));
        }

        default boolean reaches(ServerLevel level, Vec3 position) {
            return origin(level).filter(point -> point.distanceToSqr(position) <= range() * range()).isPresent();
        }

        boolean indirect();

        ItemSpec ammunition();

        default int cycleTicks() {
            return 20;
        }

        default boolean valid(ServerLevel level) {
            return at().in(level) && level.hasChunkAt(at().block(level));
        }

        /** Called with the exclusive lease registered, after the duty runner reaches its stance. */
        default boolean enter(Worker operator) {
            return true;
        }

        /** Execute one synchronous shot at the observed position. False consumes no ammunition. */
        boolean operate(Worker operator, SpottedTargets.Spot observation);

        /** Delayed integrations retain this transaction and invoke it only around native emission. */
        default Shot engage(Worker operator, SpottedTargets.Spot observation, Discharge discharge) {
            return discharge.fire(() -> operate(operator, observation));
        }

        default boolean safe(Worker operator, SpottedTargets.Spot observation) {
            return true;
        }

        void release(Worker operator);
    }

    @FunctionalInterface
    public interface Discharge {

        Shot fire(Predicate<ItemStack> nativeShot);

        default Shot fire(BooleanSupplier nativeShot) {
            return fire(round -> nativeShot.getAsBoolean());
        }
    }

    @FunctionalInterface
    public interface Finder {

        List<Station> find(ServerLevel level, ZoneView zone);
    }

    private static final String SEAT = "folkways:station_seat";
    private static final Map<ResourceLocation, Finder> FINDERS = new LinkedHashMap<>();
    private static final Map<WorldPos, Lease> LEASES = new LinkedHashMap<>();

    private WeaponStations() {
    }

    public static synchronized void register(ResourceLocation id, Finder finder) {
        if (FINDERS.putIfAbsent(id, finder) != null) {
            throw new IllegalArgumentException("station finder already registered: " + id);
        }
    }

    public static List<Station> find(ServerLevel level, ZoneView zone) {
        List<Station> found = new ArrayList<>();
        for (Finder finder : FINDERS.values()) {
            for (Station station : finder.find(level, zone)) {
                if (station.range() > 0
                    && Double.isFinite(station.range())
                    && zone.cells().stream().anyMatch(cell -> zone.at(cell).equals(station.at()))) {
                    found.add(station);
                }
            }
        }
        return List.copyOf(found);
    }

    public static Optional<Lease> acquire(Station station, Worker worker) {
        if (!(worker.body().level() instanceof ServerLevel level)
            || !level.getServer().isSameThread()
            || !station.valid(level)
            || LEASES.containsKey(station.at())
            || !worker.body().isAlive()
            || worker.body().isRemoved()) {
            return Optional.empty();
        }
        Lease lease = new Lease(station, worker);
        LEASES.put(station.at(), lease);
        boolean entered = false;
        try {
            entered = station.enter(worker);
            return entered ? Optional.of(lease) : Optional.empty();
        } finally {
            if (!entered) {
                lease.close();
            }
        }
    }

    public static final class Lease implements AutoCloseable {

        private final Station station;
        private final Worker worker;
        private final WorldPos at;
        private final UUID id = UUID.randomUUID();
        private Entity seat;
        private long nextShot;
        private boolean closed;

        private Lease(Station station, Worker worker) {
            this.station = station;
            at = station.at();
            this.worker = worker;
        }

        public Station station() {
            return station;
        }

        /** Safety and ammunition are checked in the same server tick as the integration callback. */
        public Shot fire(SpottedTargets spots, SpottedTargets.Spot observation, BooleanSupplier authorized) {
            Shot checked = check(spots, observation, authorized);
            if (checked != null) {
                return checked;
            }
            boolean[] completed = {false};
            return station.engage(
                worker,
                observation,
                nativeShot -> {
                    if (completed[0]) {
                        return Shot.COOLDOWN;
                    }
                    Shot refused = check(spots, observation, authorized);
                    if (refused != null) {
                        return refused;
                    }
                    if (!station.safe(worker, observation)) {
                        return Shot.BLOCKED;
                    }
                    ItemStack round =
                        Containers.extract(
                        worker.pack(), stack -> Goods.matches(stack, station.ammunition()), 1);
                    if (round.isEmpty()) {
                        return Shot.NO_AMMO;
                    }
                    completed[0] = true;
                    boolean emitted = false;
                    try {
                        emitted = nativeShot.test(round);
                        if (!emitted) {
                            return Shot.REFUSED;
                        }
                    } finally {
                        if (!emitted) {
                            ItemStack remaining = Containers.insert(worker.pack(), round);
                            if (!remaining.isEmpty()) {
                                worker.spill(remaining);
                            }
                        }
                    }
                    ServerLevel level = (ServerLevel) worker.body().level();
                    nextShot = level.getGameTime() + Math.max(1, station.cycleTicks());
                    return Shot.FIRED;
                });
        }

        private Shot check(SpottedTargets spots, SpottedTargets.Spot observation, BooleanSupplier authorized) {
            Mob body = worker.body();
            if (closed
                || !(body.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread()
                || !body.isAlive()
                || body.isRemoved()
                || !authorized.getAsBoolean()
                || !station.at().equals(at)
                || !station.valid(level)) {
                return Shot.UNAVAILABLE;
            }
            Optional<Mob> target = spots.resolve(observation, body);
            if (target.isEmpty() || !station.reaches(level, observation.position())) {
                return Shot.STALE;
            }
            Vec3 origin = station.origin(level).orElseThrow();
            if (!station.indirect()
                && (!body.hasLineOfSight(target.get())
                || !FireSafety.lineOfSight(body, target.get(), origin)
                || !FireSafety.clear(body, target.get(), origin))) {
                return Shot.BLOCKED;
            }
            if (level.getGameTime() < nextShot) {
                return Shot.COOLDOWN;
            }
            if (Goods.countIn(worker.pack(), station.ammunition()) < 1) {
                return Shot.NO_AMMO;
            }
            return null;
        }

        public boolean mount(Entity vehicle) {
            Mob body = worker.body();
            if (closed
                || !(body.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread()
                || vehicle.level() != body.level()
                || !vehicle.isAlive()
                || !vehicle.getPassengers().isEmpty() && body.getVehicle() != vehicle) {
                return false;
            }
            if (body.getVehicle() == vehicle && seat == vehicle) {
                return true;
            }
            if (body.isPassenger() || !body.startRiding(vehicle)) {
                return false;
            }
            seat = vehicle;
            CompoundTag loan = new CompoundTag();
            loan.putUUID("lease", id);
            loan.putUUID("vehicle", vehicle.getUUID());
            body.getPersistentData().put(SEAT, loan);
            return true;
        }

        private void dismount() {
            Mob body = worker.body();
            if (seat != null && body.getVehicle() == seat) {
                body.stopRiding();
            }
            CompoundTag loan = body.getPersistentData().getCompound(SEAT);
            if (loan.hasUUID("lease") && id.equals(loan.getUUID("lease"))) {
                body.getPersistentData().remove(SEAT);
            }
            seat = null;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                LEASES.remove(at, this);
                try {
                    station.release(worker);
                } finally {
                    dismount();
                }
            }
        }
    }

    public static boolean ownsSeat(Mob body) {
        return LEASES.values().stream()
            .anyMatch(
            lease ->
                !lease.closed
                    && lease.worker.body() == body
                    && lease.seat != null
                    && body.getVehicle() == lease.seat);
    }

    public static boolean mount(Worker worker, Entity vehicle) {
        return LEASES.values().stream()
            .filter(lease -> lease.worker.body() == worker.body())
            .findFirst()
            .filter(lease -> lease.mount(vehicle))
            .isPresent();
    }

    public static void release(UUID resident) {
        for (Lease lease : List.copyOf(LEASES.values())) {
            if (lease.worker.resident().id().equals(resident)
                || lease.seat != null && lease.seat.getUUID().equals(resident)) {
                lease.close();
            }
        }
    }

    /** Runs after recursive passenger restoration; saved leases never resume. */
    @SubscribeEvent
    public static void restored(EntityTickEvent.Post event) {
        Entity body = event.getEntity();
        if (body.level().isClientSide()
            || !body.getPersistentData().contains(SEAT)
            || body instanceof Mob mob && ownsSeat(mob)) {
            return;
        }
        CompoundTag loan = Required.compound(body.getPersistentData(), SEAT);
        Required.uuid(loan, "lease");
        UUID vehicle = Required.uuid(loan, "vehicle");
        if (body.getVehicle() != null && body.getVehicle().getUUID().equals(vehicle)) {
            body.stopRiding();
        }
        body.getPersistentData().remove(SEAT);
    }

    @SubscribeEvent
    public static void leaving(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            release(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void dying(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide()) {
            release(event.getEntity().getUUID());
        }
    }
}
