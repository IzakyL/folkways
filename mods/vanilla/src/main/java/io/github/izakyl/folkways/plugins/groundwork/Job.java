package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import java.util.List;
import net.minecraft.core.BlockPos;

// One piece of groundwork: the cell it is done at and the cells it takes. A felled tree's canopy is the leaves that
// come away with its logs; what is carried off is known by its key, the entity it is, not by where it lay.
record Job(String key, WorkSite site, BlockPos cell, List<BlockPos> cells, List<BlockPos> canopy, Stances footings) {

    Job {
        cells = List.copyOf(cells);
        canopy = List.copyOf(canopy);
    }

    static Job at(BlockPos cell, WorkSite site, Stances footings) {
        return new Job(keyOf(cell), site, cell, List.of(cell), List.of(), footings);
    }

    static String keyOf(BlockPos cell) {
        return cell.getX() + "," + cell.getY() + "," + cell.getZ();
    }
}
