package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.work.api.Seating;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Superb Warfare vehicles' numbered seats for seat stances: boarded from the ring of footings just beside the hull,
 * where the whole body stands clear of it and of anything else solid, and sat in through the native seat change.
 */
final class WarfareSeating implements Seating.Adapter {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "warfare/seats");

    @Override
    public boolean seats(Entity seat) {
        return seat instanceof VehicleEntity;
    }

    @Override
    public Set<WorldPos> footings(ServerLevel level, Entity seat, int index, Mob body) {
        Set<WorldPos> found = new LinkedHashSet<>();
        for (BlockPos cell : ring(level, (VehicleEntity) seat, body)) {
            found.add(WorldPos.of(level, cell));
        }
        return found;
    }

    /** The cells beside the hull a body can stand in: clear of it, within a step of it, and free of collisions. */
    static Set<BlockPos> ring(ServerLevel level, VehicleEntity vehicle, Mob body) {
        AABB hull = vehicle.getBoundingBox();
        Set<BlockPos> found = new LinkedHashSet<>();
        BlockPos from = BlockPos.containing(hull.minX - 1, hull.minY - 1, hull.minZ - 1);
        BlockPos to = BlockPos.containing(hull.maxX + 1, hull.minY + 1, hull.maxZ + 1);
        for (BlockPos cell : BlockPos.betweenClosed(from, to)) {
            if (!level.hasChunkAt(cell) || !Footing.withFeetAt(level, cell)) {
                continue;
            }
            AABB standing = body.getBoundingBox().move(Vec3.atBottomCenterOf(cell).subtract(body.position()));
            if (!standing.intersects(hull) && standing.inflate(1, 0, 1).intersects(hull)
                && level.noCollision(body, standing)) {
                found.add(cell.immutable());
            }
        }
        return found;
    }

    @Override
    public boolean in(Entity seat, int index, Mob body) {
        return body.getVehicle() == seat && ((VehicleEntity) seat).getSeatIndex(body) == index;
    }

    @Override
    public boolean temporary(Entity seat, int index, Mob body) {
        // Assigned crew keep their native ride between jobs and across reloads.
        return !VehicleSeats.holds((VehicleEntity) seat, body);
    }

    @Override
    public boolean sit(Entity seat, int index, Mob body) {
        VehicleEntity vehicle = (VehicleEntity) seat;
        if (vehicle.getLocked() || vehicle.isWreck() || vehicle.getNthEntity(index) != null) {
            return false;
        }
        if (body.getVehicle() != vehicle && !body.startRiding(vehicle)) {
            return false;
        }
        if (vehicle.getSeatIndex(body) != index && !vehicle.changeSeat(body, index)) {
            body.stopRiding();
            return false;
        }
        return true;
    }
}
