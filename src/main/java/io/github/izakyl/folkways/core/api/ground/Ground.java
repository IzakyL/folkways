package io.github.izakyl.folkways.core.api.ground;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

public interface Ground {

    boolean known(BlockPos cell);

    BlockState state(BlockPos cell);

    LevelReader level();

    boolean standable(BlockPos feet);

    boolean reachable(BlockPos feet);

    List<BlockPos> stancesFor(BlockPos target);

    // Which stretch of the ground come to holds the cell, or -1 for a cell that is not come to: a stretch is walked
    // both ways between any two of its cells, and not between one of its cells and another stretch's.
    int stretch(BlockPos feet);

    // Every cell come to within arm's reach of the target, seen from there or not, the nearest first.
    List<BlockPos> withinReachOf(BlockPos target);

    Collection<BlockPos> lostSince(Ground earlier);

    Ground with(Map<BlockPos, BlockState> changes);
}
