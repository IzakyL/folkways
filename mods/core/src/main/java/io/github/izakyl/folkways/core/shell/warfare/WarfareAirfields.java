package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Airport operations used by native flight control. Gameplay supplies airport choice and bookings. */
public interface WarfareAirfields {
    public static void install(WarfareAirfields airfields) {
        Holder.airfields = Objects.requireNonNull(airfields);
    }

    public static WarfareAirfields get() {
        return Holder.airfields;
    }

    default List<LandingSites.Site> sites(VehicleEntity vehicle) {
        return List.of();
    }

    default Optional<String> home(VehicleEntity vehicle) {
        return Optional.empty();
    }

    default Optional<LandingSites.Site> assign(VehicleEntity vehicle, boolean departing, Optional<String> airport) {
        return Optional.empty();
    }

    default void landed(VehicleEntity vehicle, String airport, boolean explicit) {
    }

    default boolean offField(VehicleEntity vehicle) {
        return true;
    }

    default boolean acquire(VehicleEntity vehicle, LandingSites.Site site, boolean departing) {
        return true;
    }

    default boolean acquire(VehicleEntity vehicle, LandingSites.Site site) {
        return true;
    }

    default void release(VehicleEntity vehicle) {
    }

    public default boolean airborne(VehicleEntity vehicle) {
        if (vehicle.onGround()) {
            return false;
        }
        return sites(vehicle).stream().min(Comparator.comparingDouble(site -> distance(vehicle, site)))
            .map(site -> airborne(vehicle, site)).orElseGet(() -> vehicle.getY() > vehicle.level().getHeight(
                Heightmap.Types.MOTION_BLOCKING,
                vehicle.getBlockX(), vehicle.getBlockZ()) + vehicle.getBbHeight());
    }

    public static boolean airborne(VehicleEntity vehicle, LandingSites.Site site) {
        return !vehicle.onGround() && site.pose(vehicle.level()).map(pose -> vehicle.position()
            .subtract(pose.position()).dot(pose.up()) > vehicle.getBbHeight()).orElse(false);
    }

    public static double span(VehicleEntity vehicle, Vec3 axis) {
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

    public static double length(VehicleEntity vehicle) {
        return span(vehicle, vehicle.getViewVector(1));
    }

    public static double distance(VehicleEntity vehicle, LandingSites.Site site) {
        var pose = site.pose(vehicle.level()).orElseThrow();
        double along = site.kind() == LandingSites.Kind.RUNWAY_END
            ? Math.clamp(vehicle.position().subtract(pose.position()).dot(pose.forward()), 0, site.length()) : 0;
        return pose.position().add(pose.forward().scale(along)).distanceToSqr(vehicle.position());
    }

    final class Holder {
        private static WarfareAirfields airfields = new WarfareAirfields() {
        };

        private Holder() {
        }
    }
}
