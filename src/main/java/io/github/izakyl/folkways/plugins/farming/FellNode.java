package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

final class FellNode extends FarmNode {

    private static final int CANOPY_KINDS = 3;
    private static final ItemSpec LOGS = ItemSpec.of(ItemTags.LOGS);

    private final Crop crop;

    FellNode(Chore chore) {
        super(FarmNode.declare(chore, Workload.Once.of(
            FarmingContent.ticksFor(FarmingContent.FELL_TICKS_PER_LOG, chore.job().cells().size()
                + chore.job().canopy().size()
                    * (double) FarmingContent.FELL_TICKS_PER_LEAF / FarmingContent.FELL_TICKS_PER_LOG),
            FarmingContent::haste)).leaves(leaves(chore)).doing(FarmingContent.FELLING).done(), chore);
        this.crop = chore.crop();
    }

    // A log for each cell, and from the canopy a sapling, sticks and perhaps fruit, none of them told beforehand.
    private static List<Amount> leaves(Chore chore) {
        List<Amount> left = new ArrayList<>(List.of(new Amount(LOGS, chore.job().cells().size())));
        left.addAll(Leaves.room(Math.min(CANOPY_KINDS, chore.job().canopy().size())));
        return left;
    }

    // Every block of the tree sheds a little as it goes, and each kind of block is heard once.
    @Override
    Outcome work(ServerLevel level, Worker who, Job tree) {
        List<ItemStack> logs = new ArrayList<>();
        List<WorkNoise> heard = new ArrayList<>();
        Set<Block> voiced = new HashSet<>();
        int cut = 0;
        for (BlockPos cell : tree.cells()) {
            if (!level.isLoaded(cell)) {
                continue;
            }
            BlockState state = level.getBlockState(cell);
            if (!crop.cuts(state)) {
                continue;
            }
            logs.addAll(Yield.take(level, who.body(), cell, state));
            shed(heard, voiced, cell, state);
            cut++;
        }
        for (BlockPos leaf : tree.canopy()) {
            if (!level.isLoaded(leaf)) {
                continue;
            }
            BlockState state = level.getBlockState(leaf);
            if (!Habit.Tree.leaf(state)) {
                continue;
            }
            logs.addAll(Yield.take(level, who.body(), leaf, state));
            shed(heard, voiced, leaf, state);
        }
        return new Outcome.Done(List.copyOf(heard), List.copyOf(logs),
            Optional.of(new Xp(FarmingContent.trade(), Math.max(1, cut))));
    }

    private static void shed(List<WorkNoise> heard, Set<Block> voiced, BlockPos cell, BlockState state) {
        heard.add(WorkNoise.crumbs(cell, state));
        if (voiced.add(state.getBlock())) {
            heard.add(WorkNoise.crack(cell, state));
        }
    }
}
