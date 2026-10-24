package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

// One weed pulled by a farmer.
final class BreakNode extends GroundNode {

    private final Predicate<BlockState> taken;

    BreakNode(Chores.Chore chore, BlockState seen, Predicate<BlockState> taken) {
        super(declared(chore, seen), chore);
        this.taken = taken;
    }

    private static NodeSpec declared(Chores.Chore chore, BlockState seen) {
        return GroundNode.declare(chore, FarmingContent.trade(),
                Workload.Once.of(GroundworkContent.CELL_TICKS, FarmingContent::haste))
            .leaves(Leaves.ofBlocks(List.of(seen)))
            .doing(GroundworkContent.WEEDING, seen.getBlock()).done();
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job job) {
        BlockPos cell = job.cell();
        BlockState state = level.getBlockState(cell);
        if (state.isAir() || !taken.test(state)) {
            return Outcome.done();
        }
        if (state.getDestroySpeed(level, cell) < 0.0F) {
            return refuse(GroundRefusal.NOTHING_TO_WORK);
        }
        return new Outcome.Done(List.of(WorkNoise.broke(cell, state)),
            take(level, who.body(), cell, state, ItemStack.EMPTY), Optional.of(new Xp(FarmingContent.trade(), 1)));
    }
}
