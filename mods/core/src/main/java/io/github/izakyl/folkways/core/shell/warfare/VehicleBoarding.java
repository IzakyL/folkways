package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

public final class VehicleBoarding {
    private VehicleBoarding() {
    }

    public static final int PATH_LIMIT = 4;

    public record Approach(BlockPos cell, Path path) {
    }

    public static Optional<BlockPos> footing(VehicleEntity vehicle, Mob body) {
        return approach(vehicle, body).map(Approach::cell);
    }

    public static Optional<Approach> approach(VehicleEntity vehicle, Mob body) {
        var candidates = footings(vehicle, body.getDimensions(body.getPose())).stream()
            .sorted(Comparator.<BlockPos>comparingDouble(pos -> world(vehicle, pos).distanceToSqr(body.position()))
                .thenComparingLong(BlockPos::asLong)).limit(PATH_LIMIT).toList();
        for (BlockPos pos : candidates) {
            var path = body.getNavigation().createPath(pos, 0);
            if (path != null && path.canReach()) {
                return Optional.of(new Approach(pos, path));
            }
        }
        // Ground navigation can be unavailable while a newly spawned/dismounted resident settles.
        return candidates.stream().findFirst().map(pos -> new Approach(pos, null));
    }

    public static boolean hasFooting(VehicleEntity vehicle, Mob body) {
        return !footings(vehicle, body.getDimensions(body.getPose())).isEmpty();
    }

    public static Vec3 world(VehicleEntity vehicle, BlockPos storage) {
        return WorldSpaces.world(vehicle.level(), WorldSpaces.at(vehicle.level(), storage))
            .orElse(Vec3.atBottomCenterOf(storage));
    }

    public static Vec3 world(VehicleEntity vehicle, Vec3 storage) {
        return WorldSpaces.containing(vehicle.level(), BlockPos.containing(storage))
            .map(frame -> frame.toWorld(storage.subtract(Vec3.atLowerCornerOf(frame.storageOrigin()))))
            .orElse(storage);
    }

    public static List<BlockPos> footings(VehicleEntity vehicle) {
        return footings(vehicle, EntityDimensions.fixed(.6f, 1.8f));
    }

    private static List<BlockPos> footings(VehicleEntity vehicle, EntityDimensions dimensions) {
        var hull = vehicle.getBoundingBox();
        WorldPos at = WorldSpaces.at(vehicle);
        BlockPos centre = WorldSpaces.storage(vehicle.level(), at).orElse(vehicle.blockPosition());
        int radius = (int) Math.ceil(vehicle.getBbWidth()) + 1;
        return BlockPos.betweenClosedStream(centre.offset(-radius, -1, -radius), centre.offset(radius, 1, radius))
            .filter(pos -> vehicle.level().hasChunkAt(pos) && Footing.withFeetAt(vehicle.level(), pos))
            .filter(pos -> WorldSpaces.at(vehicle.level(), pos).sameRealm(at))
            .filter(pos -> {
                Vec3 feet = world(vehicle, pos);
                return !hull.inflate(.1).intersects(dimensions.makeBoundingBox(feet))
                    && hull.distanceToSqr(feet) <= 9;
            })
            .map(BlockPos::immutable).toList();
    }
}
