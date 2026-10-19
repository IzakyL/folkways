package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class DrainNode extends BuildNode {

    DrainNode(BuildJob job, Ends ends) {
        super(job, ends);
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        for (WorldPos cell : job().after().keySet()) {
            level.setBlock(cell.block(level), Blocks.AIR.defaultBlockState(), 2);
        }
        for (Map.Entry<WorldPos, BlockState> cell : job().before().entrySet()) {
            level.updateNeighborsAt(cell.getKey().block(level), cell.getValue().getBlock());
        }
        return Outcome.done();
    }
}
