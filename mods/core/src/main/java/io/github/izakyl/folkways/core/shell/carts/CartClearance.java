package io.github.izakyl.folkways.core.shell.carts;

import io.github.izakyl.folkways.core.world.api.ClimbableBlocks;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.phys.AABB;

public final class CartClearance {
    // Runtime Redux 1.2.4 bounds: hand cart 1.3 x 1.1, supply cart 1.5 x 1.4.
    // Native spacing is 1.7 (hand cart: 1.0), plus half the puller width and a 0.2 dead band.
    // Reserve the corner-cutting chord of that 2.2-block trailing link as well as half the cart width.
    public static final double RADIUS = 0.75D + 2.2D * (1.0D - Math.sqrt(0.5D));
    private static final double HEIGHT = 1.4D;

    private CartClearance() {
    }

    public static boolean clear(PathfindingContext context, double x, double y, double z,
        double radius, double bodyHeight, Function<BlockPos, PathType> pathType) {
        AABB swept = new AABB(x - radius, y + 0.01D, z - radius,
            x + radius, y + Math.max(HEIGHT, bodyHeight), z + radius);
        for (BlockPos at : BlockPos.betweenClosed(Mth.floor(swept.minX), Mth.floor(y), Mth.floor(swept.minZ),
                Mth.floor(swept.maxX), Mth.floor(swept.maxY), Mth.floor(swept.maxZ))) {
            // A circular envelope follows the path; its bounding square would reject usable diagonal clearance.
            double dx = Math.max(Math.max(at.getX() - x, x - at.getX() - 1), 0);
            double dz = Math.max(Math.max(at.getZ() - z, z - at.getZ() - 1), 0);
            if (dx * dx + dz * dz >= radius * radius) {
                continue;
            }
            var state = context.getBlockState(at);
            if (ClimbableBlocks.isClimbable(state) || !state.getFluidState().isEmpty()) {
                return false;
            }
            PathType safety = pathType.apply(at);
            if (safety == PathType.BLOCKED || safety == PathType.DAMAGE_FIRE || safety == PathType.DAMAGE_OTHER
                    || safety == PathType.LAVA || safety == PathType.WATER) {
                return false;
            }
            for (AABB box : state.getCollisionShape(context.level(), at).toAabbs()) {
                if (swept.intersects(box.move(at))) {
                    return false;
                }
            }
        }
        return true;
    }
}
