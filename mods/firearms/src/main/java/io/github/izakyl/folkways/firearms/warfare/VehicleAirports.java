package io.github.izakyl.folkways.firearms.warfare;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.combat.api.AirportTraffic;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Airport choice and surface assignment, independent of the flight controller. */
final class VehicleAirports {
    private static final String HOME = "homeAirport";

    private VehicleAirports() {
    }

    static List<LandingSites.Site> sites(VehicleEntity vehicle) {
        return LandingSites.in((ServerLevel) vehicle.level()).stream()
            .filter(site -> site.airport().colony().isEmpty()
                || site.airport().colony().equals(VehicleSeats.owner(vehicle)))
            .filter(site -> site.pose(vehicle.level()).isPresent()).toList();
    }

    static void home(VehicleEntity vehicle, String airport) {
        var state = VehicleSeats.crew(vehicle);
        state.putString(HOME, airport);
        vehicle.getPersistentData().put(VehicleSeats.CREW, state);
    }

    static Optional<String> home(VehicleEntity vehicle) {
        var sites = sites(vehicle);
        String saved = VehicleSeats.crew(vehicle).getString(HOME);
        if (!saved.isEmpty() && LandingSites.registered((ServerLevel) vehicle.level(), saved)) {
            return Optional.of(saved);
        }
        return sites.stream().min(Comparator.comparingDouble(site -> distance(vehicle, site)))
            .map(site -> {
                home(vehicle, site.airport().id());
                return site.airport().id();
            });
    }

    static void landed(VehicleEntity vehicle, String airport, boolean explicit) {
        String saved = VehicleSeats.crew(vehicle).getString(HOME);
        boolean temporary = !saved.isEmpty() && !saved.equals(airport)
            && LandingSites.registered((ServerLevel) vehicle.level(), saved)
            && sites(vehicle).stream().noneMatch(site -> site.airport().id().equals(saved));
        if (explicit || !temporary) {
            home(vehicle, airport);
        }
    }

    static double span(VehicleEntity vehicle, Vec3 axis) {
        vehicle.updateOBB();
        double first = Double.POSITIVE_INFINITY;
        double last = Double.NEGATIVE_INFINITY;
        for (var obb : vehicle.getOBBs()) {
            for (var vertex : obb.getVertices()) {
                double projection = vertex.x * axis.x + vertex.y * axis.y + vertex.z * axis.z;
                first = Math.min(first, projection);
                last = Math.max(last, projection);
            }
        }
        return Math.max(vehicle.getBbWidth(), Double.isFinite(last - first) ? last - first : 0);
    }

    static double length(VehicleEntity vehicle) {
        return span(vehicle, vehicle.getViewVector(1));
    }

    static double distance(VehicleEntity vehicle, LandingSites.Site site) {
        var pose = site.pose(vehicle.level()).orElseThrow();
        double along = site.kind() == LandingSites.Kind.RUNWAY_END
            ? Math.clamp(vehicle.position().subtract(pose.position()).dot(pose.forward()), 0, site.length()) : 0;
        return pose.position().add(pose.forward().scale(along)).distanceToSqr(vehicle.position());
    }

    /** Away from every airport an aircraft departs on its own; at one it waits for a surface. */
    static boolean offField(VehicleEntity vehicle) {
        return vehicle.getEngineInfo() instanceof EngineInfo.Helicopter && !(vehicle.getEngineInfo()
            instanceof EngineInfo.Aircraft) || sites(vehicle).stream().noneMatch(site -> distance(vehicle, site) < 64 * 64);
    }

    static boolean airborne(VehicleEntity vehicle) {
        if (vehicle.onGround()) {
            return false;
        }
        return sites(vehicle).stream().min(Comparator.comparingDouble(site -> distance(vehicle, site)))
            .map(site -> airborne(vehicle, site)).orElseGet(() -> vehicle.getY() > vehicle.level().getHeight(
                Heightmap.Types.MOTION_BLOCKING,
                vehicle.getBlockX(), vehicle.getBlockZ()) + vehicle.getBbHeight());
    }

    static boolean airborne(VehicleEntity vehicle, LandingSites.Site site) {
        return !vehicle.onGround() && site.pose(vehicle.level()).map(pose -> vehicle.position()
            .subtract(pose.position()).dot(pose.up()) > vehicle.getBbHeight()).orElse(false);
    }

    static Optional<LandingSites.Site> assign(VehicleEntity vehicle, boolean departing, Optional<String> airport) {
        var sites = sites(vehicle);
        if (departing && vehicle.getEngineInfo() instanceof EngineInfo.Helicopter
                && !(vehicle.getEngineInfo() instanceof EngineInfo.Aircraft)) {
            var pad = sites.stream().filter(site -> site.kind() == LandingSites.Kind.HELIPAD)
                .filter(site -> !airborne(vehicle, site)).filter(site -> {
                    var pose = site.pose(vehicle.level()).orElseThrow();
                    var offset = vehicle.position().subtract(pose.position());
                    return Math.abs(offset.dot(pose.forward())) <= site.length() / 2
                        && Math.abs(offset.dot(pose.forward().cross(pose.up()))) <= site.width() / 2;
                }).min(Comparator.comparingDouble(site -> distance(vehicle, site)));
            pad.ifPresent(site -> {
                VehicleRunwayTraffic.acquire(vehicle, site, true);
                home(vehicle, site.airport().id());
            });
            return pad;
        }
        // Departure follows the current parking location; landing follows the persisted home.
        var destination = departing ? sites.stream().min(Comparator.comparingDouble(site -> distance(vehicle, site)))
            .map(site -> site.airport().id()) : airport.or(() -> home(vehicle));
        if (destination.isEmpty()) {
            return Optional.empty();
        }
        String preferred = destination.orElse("");
        if (!departing && airport.isEmpty() && destination.isPresent()
            && sites.stream().noneMatch(site -> site.airport().id().equals(preferred))) {
            destination = sites.stream().min(Comparator.comparingDouble(site -> distance(vehicle, site)))
                .map(site -> site.airport().id());
        }
        final var selected = destination;
        var needs = DirectVehicleMotion.runway(vehicle);
        double width = span(vehicle, vehicle.getRightVec(1));
        double length = length(vehicle);
        var candidates = sites.stream().map(site -> new LandingSites.Site(site.id(), site.name(), site.kind(),
                site.position(), site.yaw(), site.length(), site.width(), site.catapults().stream()
                    .filter(catapult -> VehicleCatapults.available((ServerLevel) vehicle.level(), catapult))
                    .toList(), site.airport())).filter(site -> selected.get().equals(site.airport().id()))
            .filter(site -> vehicle.getEngineInfo() instanceof EngineInfo.Aircraft
                ? needs.filter(value -> value.fits(site, departing, 0)).isPresent()
                : site.kind() == LandingSites.Kind.HELIPAD && site.width() >= width && site.length() >= length)
            .sorted(Comparator.<LandingSites.Site>comparingInt(site -> departing && site.catapults().stream()
                .anyMatch(catapult -> VehicleCatapults.requiredLength(vehicle, site, catapult) <= site.length()) ? 0 : 1)
                .thenComparingInt(site -> needs
                    .filter(value -> departing && site.length() < value.takeoff()).isPresent() ? 1 : 0)
                .thenComparingDouble(site -> distance(vehicle, site)).thenComparing(LandingSites.Site::id))
            .toList();
        var waiting = VehicleRunwayTraffic.waiting(vehicle);
        if (waiting.isPresent() && candidates.stream().noneMatch(site -> site.id().equals(waiting.get()))) {
            VehicleRunwayTraffic.release(vehicle);
        }
        for (var site : candidates) {
            if (waiting.isPresent() && candidates.stream().anyMatch(value -> value.id().equals(waiting.get()))
                    && !site.id().equals(waiting.get())) {
                continue;
            }
            if (occupied(vehicle, site)) {
                continue;
            }
            if (VehicleRunwayTraffic.acquire(vehicle, site, departing)) {
                if (departing) {
                    home(vehicle, destination.get());
                }
                return Optional.of(site);
            }
            if (AirportTraffic.available()) {
                return Optional.empty();
            }
            VehicleRunwayTraffic.release(vehicle);
        }
        return Optional.empty();
    }

    /**
     * Whether another vehicle stands on the site's own surface: the runway's strip from threshold to far end, or the
     * pad's square, both in the site's frame. The world box around a turned runway (a diagonal one, or one on a
     * turned deck) also takes in whatever is parked beside it, so the test is made along and across the site.
     */
    static boolean occupied(VehicleEntity vehicle, LandingSites.Site site) {
        var pose = site.pose(vehicle.level()).orElseThrow();
        boolean runway = site.kind() == LandingSites.Kind.RUNWAY_END;
        Vec3 forward = pose.forward();
        Vec3 across = forward.cross(pose.up()).normalize();
        double half = site.width() / 2;
        double from = runway ? 0 : -half;
        double to = runway ? site.length() : half;
        var end = pose.position().add(forward.scale(to));
        var bounds = new AABB(pose.position().add(forward.scale(from)), end).inflate(half, vehicle.getBbHeight(), half);
        return !vehicle.level().getEntitiesOfClass(VehicleEntity.class, bounds, other -> {
            if (other == vehicle || other.isWreck()) {
                return false;
            }
            Vec3 offset = other.position().subtract(pose.position());
            double reach = other.getBbWidth() / 2;
            double along = offset.dot(forward);
            return along >= from - reach && along <= to + reach && Math.abs(offset.dot(across)) <= half + reach
                && Math.abs(offset.dot(pose.up())) <= vehicle.getBbHeight() + other.getBbHeight();
        }).isEmpty();
    }
}
