package io.github.izakyl.folkways.firearms.warfare;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.combat.api.AirportTraffic;
import net.minecraft.server.level.ServerLevel;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Both ends, landing and catapult departure share the same FIFO. */
final class VehicleRunwayTraffic {
    private static final Map<String, LinkedHashSet<VehicleEntity>> QUEUES = new HashMap<>();

    private record Booking(String site, AirportTraffic.Claim claim) {
    }

    private static final Map<VehicleEntity, Booking> BOOKINGS = new HashMap<>();

    private static boolean valid(VehicleEntity hull) {
        return !hull.isRemoved() && !hull.isWreck() && VehicleSeats.owner(hull).isPresent()
            && hull.getNthEntity(0) instanceof LivingEntity driver && VehicleSeats.holds(hull, driver);
    }

    private VehicleRunwayTraffic() {
    }

    static boolean acquire(VehicleEntity vehicle, LandingSites.Site site) {
        return acquire(vehicle, site, false);
    }

    static boolean acquire(VehicleEntity vehicle, LandingSites.Site site, boolean departing) {
        for (var hull : java.util.List.copyOf(BOOKINGS.keySet())) {
            if (!valid(hull)) {
                release(hull);
            }
        }
        var booking = BOOKINGS.get(vehicle);
        if (booking != null && (!booking.site().equals(site.id())
                || booking.claim().state() == AirportTraffic.State.REVOKED
                || booking.claim().state() == AirportTraffic.State.RELEASED)) {
            release(vehicle);
            booking = null;
        }
        if (booking == null) {
            var claim = AirportTraffic.assign((ServerLevel) vehicle.level(), site,
                new AirportTraffic.Aircraft(vehicle.getUUID(), VehicleAirports.length(vehicle),
                    DirectVehicleMotion.runway(vehicle).map(needs -> needs.width())
                        .orElse((double) vehicle.getBbWidth()), departing));
            if (claim.isPresent()) {
                booking = new Booking(site.id(), claim.orElseThrow());
                BOOKINGS.put(vehicle, booking);
            }
        }
        if (booking != null) {
            return booking.claim().state() == AirportTraffic.State.HELD;
        }
        if (AirportTraffic.available()) {
            return false;
        }
        Vec3 center = site.position().cell().getCenter()
            .add(Vec3.directionFromRotation(0, site.yaw()).scale((site.length() - 1) / 2));
        String key = vehicle.level().dimension().location() + "/" + site.position().realm() + "/"
            + Math.round(center.x * 2) + "/" + Math.round(center.y * 2) + "/" + Math.round(center.z * 2);
        var queue = QUEUES.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
        queue.removeIf(hull -> hull.isRemoved() || hull.isWreck() || VehicleSeats.owner(hull).isEmpty()
            || !(hull.getNthEntity(0) instanceof LivingEntity driver) || !VehicleSeats.holds(hull, driver));
        queue.add(vehicle);
        return queue.getFirst() == vehicle;
    }

    static java.util.Optional<String> waiting(VehicleEntity vehicle) {
        var booking = BOOKINGS.get(vehicle);
        return booking != null && booking.claim().state() == AirportTraffic.State.WAITING
            ? java.util.Optional.of(booking.site()) : java.util.Optional.empty();
    }

    static void release(VehicleEntity vehicle) {
        var booking = BOOKINGS.remove(vehicle);
        if (booking != null) {
            booking.claim().close();
        }
        QUEUES.values().forEach(queue -> queue.remove(vehicle));
        QUEUES.values().removeIf(LinkedHashSet::isEmpty);
    }
}
