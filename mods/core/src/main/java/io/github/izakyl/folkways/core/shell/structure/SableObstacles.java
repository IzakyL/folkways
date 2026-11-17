package io.github.izakyl.folkways.core.shell.structure;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import io.github.izakyl.folkways.core.world.api.Structures;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

public final class SableObstacles implements Structures.Obstacles {

    @Override
    public List<AABB> in(ServerLevel level, AABB area) {
        return in(level, area, null);
    }

    @Override
    public List<AABB> in(ServerLevel level, AABB area, java.util.UUID excluded) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return List.of();
        }
        List<AABB> found = new ArrayList<>();
        for (var ship : container.getAllSubLevels()) {
            if (ship.isRemoved() || excluded != null && ship == container.getSubLevel(excluded)) {
                continue;
            }
            var plot = ship.getPlot().getBoundingBox();
            // Bound the search in plot coordinates before reading blocks, including on large structures.
            AABB local = bounds(ship.logicalPose(), area, true);
            int minX = Math.max(plot.minX(), Mth.floor(local.minX));
            int minY = Math.max(plot.minY(), Mth.floor(local.minY));
            int minZ = Math.max(plot.minZ(), Mth.floor(local.minZ));
            int maxX = Math.min(plot.maxX(), Mth.floor(local.maxX));
            int maxY = Math.min(plot.maxY(), Mth.floor(local.maxY));
            int maxZ = Math.min(plot.maxZ(), Mth.floor(local.maxZ));
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                continue;
            }
            for (BlockPos cell : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
                if (!level.hasChunkAt(cell)) {
                    continue;
                }
                for (AABB shape : level.getBlockState(cell).getCollisionShape(level, cell).toAabbs()) {
                    AABB world = bounds(ship.logicalPose(), shape.move(cell), false);
                    if (world.intersects(area)) {
                        found.add(world);
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    private static AABB bounds(Pose3dc pose, AABB box, boolean inverse) {
        AABB bounds = null;
        for (int corner = 0; corner < 8; corner++) {
            Vector3d point = new Vector3d((corner & 1) == 0 ? box.minX : box.maxX,
                (corner & 2) == 0 ? box.minY : box.maxY, (corner & 4) == 0 ? box.minZ : box.maxZ);
            point = inverse ? pose.transformPositionInverse(point) : pose.transformPosition(point);
            AABB one = new AABB(point.x, point.y, point.z, point.x, point.y, point.z);
            bounds = bounds == null ? one : bounds.minmax(one);
        }
        return bounds;
    }
}
