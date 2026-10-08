package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.terms.Goods;
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

final class HarvestNode extends FarmNode {

    private final Crop crop;

    HarvestNode(Chore chore) {
        super(FarmNode.declare(chore, Workload.Once.of(FarmingContent.CELL_TICKS,
            FarmingContent::haste))
            .doing(FarmingContent.HARVESTING, chore.batch().crop().fruit()).done(), chore);
        this.crop = chore.batch().crop();
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job job) {
        List<ItemStack> taken = new ArrayList<>();
        List<WorkNoise> heard = new ArrayList<>();
        boolean replants = who.rankOf(FarmingContent.REPLANT) > 0;
        boolean cut = false;
        int broken = 0;
        for (BlockPos cell : job.cells()) {
            if (!level.isLoaded(cell)) {
                continue;
            }
            BlockState state = level.getBlockState(cell);
            if (!crop.cuts(state)) {
                cut = true;
                continue;
            }
            taken.addAll(Yield.take(level, who.body(), cell, state));
            heard.add(WorkNoise.broke(cell, state));
            broken++;
            cut = true;
            if (replants) {
                replant(level, cell, taken).ifPresent(heard::add);
            }
        }
        if (!cut) {
            return refuse(FarmRefusal.NOTHING_TO_WORK);
        }
        List<ItemStack> yielded = new ArrayList<>(taken.size());
        for (ItemStack stack : taken) {
            if (!stack.isEmpty()) {
                yielded.add(stack);
            }
        }
        return new Outcome.Done(List.copyOf(heard), List.copyOf(yielded),
            Optional.of(new Xp(FarmingContent.trade(), Math.max(1, broken))));
    }

    private Optional<WorkNoise> replant(ServerLevel level, BlockPos cell, List<ItemStack> taken) {
        if (!crop.cutWhole() || !crop.sown().canSurvive(level, cell)) {
            return Optional.empty();
        }
        for (ItemStack stack : taken) {
            if (stack.isEmpty() || !Goods.matches(stack, crop.seedSpec())) {
                continue;
            }
            stack.shrink(1);
            level.setBlock(cell, crop.sown(), 3);
            return Optional.of(WorkNoise.placed(cell, crop.sown()));
        }
        return Optional.empty();
    }
}
