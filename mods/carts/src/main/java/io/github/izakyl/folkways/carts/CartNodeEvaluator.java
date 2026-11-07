package io.github.izakyl.folkways.carts;

import io.github.izakyl.folkways.core.world.api.ClimbableBlocks;
import io.github.izakyl.folkways.plugins.person.walk.ResidentNodeEvaluator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.phys.AABB;

class CartNodeEvaluator extends ResidentNodeEvaluator {
    // Runtime Redux 1.2.4 bounds: hand cart 1.3 x 1.1, supply cart 1.5 x 1.4.
    // Native spacing is 1.7 (hand cart: 1.0), plus half the puller width and a 0.2 dead band.
    // Reserve the corner-cutting chord of that 2.2-block trailing link as well as half the cart width.
    private static final double RADIUS = 0.75D + 2.2D * (1.0D - Math.sqrt(0.5D));
    private static final double HEIGHT = 1.4D;

    CartNodeEvaluator(@Nullable BlockPos start) {
        super(start);
    }

    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
        PathType type = super.getPathType(context, x, y, z);
        return clear(context, x + 0.5D, y, z + 0.5D) ? type : PathType.BLOCKED;
    }

    protected double radius() {
        return RADIUS;
    }

    private boolean clear(PathfindingContext context, double x, double y, double z) {
        double radius = radius();
        AABB swept = new AABB(x - radius, y + 0.01D, z - radius,
            x + radius, y + Math.max(HEIGHT, mob.getBbHeight()), z + radius);
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
            PathType safety = super.getPathType(context, at.getX(), at.getY(), at.getZ());
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

    @Override
    public int getNeighbors(Node[] neighbors, Node node) {
        int count = super.getNeighbors(neighbors, node);
        int kept = 0;
        for (int i = 0; i < count; i++) {
            Node next = neighbors[i];
            // No jumps, drops or climbs: sample the edge too, so diagonal nodes cannot cut a wall.
            if (next.y == node.y && clear(currentContext, (node.x + next.x) / 2.0D + 0.5D,
                    node.y, (node.z + next.z) / 2.0D + 0.5D)) {
                neighbors[kept++] = next;
            }
        }
        return kept;
    }
}
