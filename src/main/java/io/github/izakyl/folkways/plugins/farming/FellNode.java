package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

final class FellNode extends FarmNode {

    private final Crop crop;

    FellNode(Chore chore) {
        super(FarmNode.declare(chore, Workload.Once.of(
            FarmingContent.FELL_TICKS_PER_LOG * chore.job().cells().size()
                + FarmingContent.FELL_TICKS_PER_LEAF * chore.job().canopy().size(),
            FarmingContent::haste)).doing(FarmingContent.FELLING).done(), chore);
        this.crop = chore.batch().crop();
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job tree) {
        List<ItemStack> logs = new ArrayList<>();
        List<WorkNoise> heard = new ArrayList<>();
        for (BlockPos cell : tree.cells()) {
            if (!level.isLoaded(cell)) {
                continue;
            }
            BlockState state = level.getBlockState(cell);
            if (!crop.cuts(state)) {
                continue;
            }
            logs.addAll(Yield.take(level, who.body(), cell, state));
            heard.add(WorkNoise.broke(cell, state));
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
        }
        return new Outcome.Done(List.copyOf(heard), List.copyOf(logs),
            Optional.of(new Xp(FarmingContent.trade(), Math.max(1, heard.size()))));
    }
}
