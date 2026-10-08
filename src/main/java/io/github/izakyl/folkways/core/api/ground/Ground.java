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

    Collection<BlockPos> lostSince(Ground earlier);

    Ground with(Map<BlockPos, BlockState> changes);
}
