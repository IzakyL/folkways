package io.github.izakyl.folkways.vanilla.carts;

import io.github.izakyl.folkways.core.shell.carts.CartClearance;
import io.github.izakyl.folkways.vanilla.person.walk.ResidentNodeEvaluator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;

class CartNodeEvaluator extends ResidentNodeEvaluator {
    CartNodeEvaluator(@Nullable BlockPos start) {
        super(start);
    }

    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
        PathType type = super.getPathType(context, x, y, z);
        return clear(context, x + 0.5D, y, z + 0.5D) ? type : PathType.BLOCKED;
    }

    protected double radius() {
        return CartClearance.RADIUS;
    }

    private boolean clear(PathfindingContext context, double x, double y, double z) {
        return CartClearance.clear(context, x, y, z, radius(), mob.getBbHeight(),
            at -> super.getPathType(context, at.getX(), at.getY(), at.getZ()));
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
