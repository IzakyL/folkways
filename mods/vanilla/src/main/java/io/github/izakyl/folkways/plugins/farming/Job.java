package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import java.util.List;
import net.minecraft.core.BlockPos;

// The canopy is what comes away with the cells: the leaves of a felled tree.
record Job(WorldPos ground, List<BlockPos> cells, List<BlockPos> canopy, Stances footings, BlockPos storage) {

    Job {
        cells = List.copyOf(cells);
        canopy = List.copyOf(canopy);
    }

    Job(WorldPos ground, List<BlockPos> cells, Stances footings, BlockPos storage) {
        this(ground, cells, List.of(), footings, storage);
    }

    BlockPos cell() {
        return storage;
    }
}
