package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import io.github.izakyl.folkways.plugins.farming.Felling;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

// A whole tree felled as a wood lot fells it, logs and the leaves they hold up, and nothing planted back.
final class FellNode extends GroundNode {

    private static final int CANOPY_KINDS = 3;

    FellNode(ServerLevel level, Chores.Chore chore) {
        super(GroundNode.declare(chore, FarmingContent.trade(), Workload.Once.of(
            GroundworkContent.ticksFor(GroundworkContent.FELL_TICKS_PER_LOG, chore.job().cells().size()
                + chore.job().canopy().size()
                    * (double) GroundworkContent.FELL_TICKS_PER_LEAF / GroundworkContent.FELL_TICKS_PER_LOG),
            FarmingContent::haste)).leaves(leaves(level, chore.job())).doing(GroundworkContent.FELLING).done(), chore);
    }

    // The logs as they stand, and from the canopy a sapling, sticks and perhaps fruit, none of them told beforehand.
    private static List<Amount> leaves(ServerLevel level, Job tree) {
        List<BlockState> logs = new ArrayList<>();
        for (BlockPos log : tree.cells()) {
            if (level.isLoaded(log)) {
                logs.add(level.getBlockState(log));
            }
        }
        List<Amount> left = new ArrayList<>(Leaves.ofBlocks(logs));
        left.addAll(Leaves.room(Math.min(CANOPY_KINDS, tree.canopy().size())));
        return left;
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job tree) {
        List<ItemStack> taken = new ArrayList<>();
        List<WorkNoise> heard = new ArrayList<>();
        Set<Block> voiced = new HashSet<>();
        int cut = 0;
        for (BlockPos log : tree.cells()) {
            BlockState state = level.isLoaded(log) ? level.getBlockState(log) : null;
            if (state == null || !state.is(BlockTags.LOGS)) {
                continue;
            }
            taken.addAll(take(level, who.body(), log, state, ItemStack.EMPTY));
            shed(heard, voiced, log, state);
            cut++;
        }
        if (cut == 0) {
            return refuse(GroundRefusal.NOTHING_TO_WORK);
        }
        for (BlockPos leaf : tree.canopy()) {
            BlockState state = level.isLoaded(leaf) ? level.getBlockState(leaf) : null;
            if (state == null || !Felling.leaf(state)) {
                continue;
            }
            taken.addAll(take(level, who.body(), leaf, state, ItemStack.EMPTY));
            shed(heard, voiced, leaf, state);
        }
        return new Outcome.Done(List.copyOf(heard), List.copyOf(taken),
            Optional.of(new Xp(FarmingContent.trade(), cut)));
    }

    private static void shed(List<WorkNoise> heard, Set<Block> voiced, BlockPos cell, BlockState state) {
        heard.add(WorkNoise.crumbs(cell, state));
        if (voiced.add(state.getBlock())) {
            heard.add(WorkNoise.crack(cell, state));
        }
    }
}
