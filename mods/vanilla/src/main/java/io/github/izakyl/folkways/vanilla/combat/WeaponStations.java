package io.github.izakyl.folkways.vanilla.combat;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.ShotPlanning;
import io.github.izakyl.folkways.core.combat.api.SpottedTargets.Spot;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.Lease;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Fixed weapons as sentries fire them. A station is a gun position's controls with the gameplay of firing them
 * (range, ammunition, cycle, who may man it): the position is its aim {@link Signal}, found through {@link Controls}
 * and manned under a Core {@link Lease}, and it is fired by pressing its trigger or by loading its breech. Every write
 * is answered at once. Discovery never takes the lease.
 */
public final class WeaponStations {

    /** How an engagement stood after one try. */
    public enum Engagement {

        FIRED,
        COOLDOWN,
        NO_AMMO,
        BLOCKED,
        STALE,
        UNAVAILABLE
    }

    /** How laying the gun on a position went. */
    public enum Laying {

        LAID,
        // Written, and the gun is still turning.
        TURNING,
        NO_SOLUTION,
        REFUSED
    }

    /**
     * A gun position with its gameplay. Its lease key, validity, stance and enter / leave are the position's, plus
     * whatever the engagement keeps per operator.
     */
    public interface Station extends Signal {

        default boolean planned(ServerLevel level, Colony colony,
                                Mob operator, Mob target) {
            var emitter = entityId().map(level::getEntity).orElse(operator);
            return origin(level).filter(from -> ShotPlanning.avoidsColony(level, colony, emitter,
                List.of(from, target.getBoundingBox().getCenter()), .35, 0)).isPresent();
        }


        double range();

        default Optional<Vec3> origin(ServerLevel level) {
            return WorldSpaces.world(level, anchor().above()).map(point -> point.add(0, .5, 0));
        }

        default boolean reaches(ServerLevel level, Vec3 position) {
            return origin(level).filter(point -> point.distanceToSqr(position) <= range() * range()).isPresent();
        }

        boolean indirect();

        ItemSpec ammunition();

        default int cycleTicks() {
            return 20;
        }

        default double damage() {
            return 20;
        }

        default boolean loaded(ServerLevel level, Worker worker) {
            return Goods.countIn(worker.pack(), ammunition()) > 0;
        }

        /** Writes the aim that lays the gun on the observed position. A station without an aim is always laid. */
        default Laying lay(ServerLevel level, Mob operator, Vec3 at) {
            return Laying.LAID;
        }

        /** The trigger the station is fired with, when firing is a press. */
        default Optional<Signal> trigger(ServerLevel level) {
            return Optional.empty();
        }

        /** The gun's own supply its trigger fires from, which the operator feeds from its pack when it runs dry. */
        default Optional<Port<ItemStack>> magazine(ServerLevel level) {
            return Optional.empty();
        }

        /** Where one round goes to be fired by the gun's own working, when firing is loading. */
        default Optional<Port<ItemStack>> breech(ServerLevel level) {
            return Optional.empty();
        }
    }

    /**
     * Turns a mount found by {@link Controls} into a station, or empty when it is not one this integration fires. The
     * same station is returned while the same weapon exists. A signal that is already a station needs no arming.
     */
    @FunctionalInterface
    public interface Arming {

        Optional<Station> arm(ServerLevel level, Signal mount);
    }

    private static final Map<ResourceLocation, Arming> ARMINGS = new LinkedHashMap<>();

    private WeaponStations() {
    }

    public static synchronized void register(ResourceLocation id, Arming arming) {
        if (ARMINGS.putIfAbsent(id, arming) != null) {
            throw new IllegalArgumentException("station arming already registered: " + id);
        }
    }

    /** The stations whose mounts stand at this cell. */
    public static List<Station> at(ServerLevel level, WorldPos at) {
        List<Station> found = new ArrayList<>();
        for (Signal signal : Controls.signals(level, at)) {
            arm(level, signal)
                .filter(station -> station.range() > 0 && Double.isFinite(station.range()))
                .ifPresent(found::add);
        }
        return List.copyOf(found);
    }

    private static Optional<Station> arm(ServerLevel level, Signal signal) {
        if (signal instanceof Station station) {
            return Optional.of(station);
        }
        for (Arming arming : ARMINGS.values()) {
            Optional<Station> station = arming.arm(level, signal);
            if (station.isPresent()) {
                return station;
            }
        }
        return Optional.empty();
    }

    /**
     * Mans the station under a Core lease. Unlike {@link Lease#take}, a station already held is refused even to its
     * own holder: a station has one operator and one engagement.
     */
    public static Optional<Manning> take(Station station, Worker worker) {
        return lease(station, worker).map(WeaponStations::manning);
    }

    public static Optional<Lease> lease(Station station, Worker worker) {
        return Lease.held(station).isPresent() || !station.admits(worker.body())
            ? Optional.empty() : Lease.take(station, worker);
    }

    public static Manning manning(Lease lease) {
        return new Manning((Station) lease.control(), lease);
    }

    /** An operator at a station: its lease, and the engagement's cycle. Closing it ends the lease. */
    public static final class Manning implements AutoCloseable {

        private final Station station;
        private final Lease lease;
        private final WorldPos at;
        private long nextShot;

        private Manning(Station station, Lease lease) {
            this.station = station;
            this.lease = lease;
            at = station.anchor();
        }

        public Station station() {
            return station;
        }

        public Lease lease() {
            return lease;
        }

        /**
         * Lays the gun and fires it by
         * pressing its trigger (feeding the gun a carried round when it has none) or by loading a carried round.
         */
        public Engagement fire(SpottedTargets spots, Spot observation, BooleanSupplier authorized) {
            Engagement checked = check(spots, observation, authorized);
            if (checked != null) {
                return checked;
            }
            Mob body = lease.worker().body();
            ServerLevel level = (ServerLevel) body.level();
            switch (station.lay(level, body, observation.position())) {
                case NO_SOLUTION -> {
                    return Engagement.BLOCKED;
                }
                case REFUSED -> {
                    return Engagement.UNAVAILABLE;
                }
                case TURNING -> {
                    return Engagement.COOLDOWN;
                }
                case LAID -> {
                }
            }
            Optional<Signal> trigger = station.trigger(level);
            Optional<Port<ItemStack>> breech = station.breech(level);
            Optional<Control> firing = trigger.<Control>map(Control.class::cast)
                .or(() -> breech.map(Control.class::cast));
            if (firing.isEmpty()) {
                return Engagement.UNAVAILABLE;
            }
            Engagement fired = trigger.isPresent() ? press(level, body, trigger.get()) : load(level, body, breech.get());
            if (fired == Engagement.FIRED) {
                nextShot = level.getGameTime() + Math.max(1, station.cycleTicks());
            }
            return fired;
        }

        // The gun fires from its own supply; when it has nothing left, the operator puts one carried round in, and the
        // gun's own reload takes it from there.
        private Engagement press(ServerLevel level, Mob body, Signal trigger) {
            Signal.Write pressed = trigger.write(level, body, Value.PULSE);
            if (pressed == Signal.Write.REFUSED) {
                Optional<Port<ItemStack>> magazine = station.magazine(level);
                if (magazine.isEmpty() || !feed(level, body, magazine.get())) {
                    return Engagement.NO_AMMO;
                }
                pressed = trigger.write(level, body, Value.PULSE);
            }
            return switch (pressed) {
                case WRITTEN -> Engagement.FIRED;
                case BUSY -> Engagement.COOLDOWN;
                case REFUSED -> Engagement.NO_AMMO;
            };
        }

        private boolean feed(ServerLevel level, Mob body, Port<ItemStack> magazine) {
            Container pack = lease.worker().pack();
            for (int slot = 0; slot < pack.getContainerSize(); slot++) {
                ItemStack carried = pack.getItem(slot);
                if (!carried.isEmpty() && Goods.matches(carried, station.ammunition())
                    && !magazine.deposit(level, body, carried.copyWithCount(1), true).isEmpty()) {
                    ItemStack round = pack.removeItem(slot, 1);
                    ItemStack taken = magazine.deposit(level, body, round, false);
                    if (taken.isEmpty()) {
                        ItemStack left = Containers.insert(pack, round);
                        if (!left.isEmpty()) {
                            lease.worker().spill(left);
                        }
                        return false;
                    }
                    return true;
                }
            }
            return false;
        }

        // One carried round goes into the breech, which fires it by itself.
        private Engagement load(ServerLevel level, Mob body, Port<ItemStack> breech) {
            Container pack = lease.worker().pack();
            for (int slot = 0; slot < pack.getContainerSize(); slot++) {
                ItemStack carried = pack.getItem(slot);
                if (carried.isEmpty() || !Goods.matches(carried, station.ammunition())) {
                    continue;
                }
                if (breech.deposit(level, body, carried.copyWithCount(1), true).isEmpty()) {
                    return Engagement.COOLDOWN;
                }
                ItemStack round = pack.removeItem(slot, 1);
                if (breech.deposit(level, body, round, false).isEmpty()) {
                    ItemStack left = Containers.insert(pack, round);
                    if (!left.isEmpty()) {
                        lease.worker().spill(left);
                    }
                    return Engagement.COOLDOWN;
                }
                return Engagement.FIRED;
            }
            return Engagement.NO_AMMO;
        }

        private Engagement check(SpottedTargets spots, Spot observation, BooleanSupplier authorized) {
            Mob body = lease.worker().body();
            if (!lease.open()
                || !(body.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread()
                || !body.isAlive()
                || body.isRemoved()
                || !authorized.getAsBoolean()
                || !station.anchor().equals(at)
                || !station.valid(level)) {
                return Engagement.UNAVAILABLE;
            }
            Optional<Mob> target = spots.resolve(observation, body);
            if (target.isEmpty() || !station.reaches(level, observation.position())) {
                return Engagement.STALE;
            }
            if (level.getGameTime() < nextShot) {
                return Engagement.COOLDOWN;
            }
            return null;
        }

        @Override
        public void close() {
            lease.close();
        }
    }
}
