package io.github.izakyl.folkways.plugins.farming;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

// The cells a field covers, with the box around them for where a cell sits in its rows.
record Plot(Set<BlockPos> cells, int minX, int minZ, int width, int depth) {

    // A crop that stands in the way is sown in pairs of rows with a row left bare between them, so every stalk is
    // in reach from somewhere a body can walk out of.
    private static final int AISLE_EVERY = 3;
    private static final int AISLE = 1;

    static Plot of(Set<BlockPos> cells) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos cell : cells) {
            minX = Math.min(minX, cell.getX());
            minZ = Math.min(minZ, cell.getZ());
            maxX = Math.max(maxX, cell.getX());
            maxZ = Math.max(maxZ, cell.getZ());
        }
        return cells.isEmpty()
            ? new Plot(cells, 0, 0, 0, 0)
            : new Plot(cells, minX, minZ, maxX - minX + 1, maxZ - minZ + 1);
    }

    boolean containsAll(Iterable<BlockPos> stand) {
        for (BlockPos ground : stand) {
            if (!cells.contains(ground)) {
                return false;
            }
        }
        return true;
    }

    // Every cell beside this one is in the plot too.
    boolean inside(BlockPos ground) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!cells.contains(ground.relative(side))) {
                return false;
            }
        }
        return true;
    }

    // The aisles run the long way, so there are as few of them as can be.
    boolean aisle(BlockPos ground) {
        int across = width <= depth ? ground.getX() - minX : ground.getZ() - minZ;
        return across % AISLE_EVERY == AISLE;
    }
}
