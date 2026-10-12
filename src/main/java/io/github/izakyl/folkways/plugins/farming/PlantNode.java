package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.work.Need;
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

final class PlantNode extends FarmNode {

    private final Crop crop;

    // The job's cells are the ones to sow: one, or every bare cell of a stand that only grows sown together.
    PlantNode(Chore chore) {
        super(FarmNode.declare(chore, Workload.Once.of(
                FarmingContent.ticksFor(FarmingContent.CELL_TICKS, chore.job().cells().size()),
                FarmingContent::haste))
            .needs(new Need(chore.crop().seedSpec(), chore.job().cells().size()))
            .doing(FarmingContent.PLANTING, chore.crop().seedSpec()).done(), chore);
        this.crop = chore.crop();
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job job) {
        BlockState sown = crop.sown();
        List<WorkNoise> heard = new ArrayList<>();
        if (!level.isLoaded(job.cell()) || !crop.roomAt(level, crop.standAt(job.cell()))) {
            return refuse(FarmRefusal.NOTHING_TO_WORK);
        }
        boolean barren = false;
        for (BlockPos ground : job.cells()) {
            BlockPos base = ground.above();
            if (!level.isLoaded(base)) {
                barren = true;
                continue;
            }
            BlockState standing = level.getBlockState(base);
            if (!standing.isAir() && !standing.canBeReplaced()) {
                continue;
            }
            if (!sown.canSurvive(level, base)) {
                barren = true;
                continue;
            }
            level.setBlock(base, sown, 3);
            heard.add(WorkNoise.placed(base, sown));
        }
        if (heard.isEmpty()) {
            return barren
                ? refuse(FarmRefusal.NOTHING_TO_WORK)
                : new Outcome.Done(List.of(), List.of(), Optional.empty(), who.supplied());
        }
        return new Outcome.Done(List.copyOf(heard), List.of(),
            Optional.of(new Xp(FarmingContent.trade(), heard.size())), unsown(who.supplied(), heard.size()));
    }

    // The seed handed over for cells that turned out not to want it.
    private List<ItemStack> unsown(List<ItemStack> supplied, int used) {
        List<ItemStack> back = new ArrayList<>(supplied.size());
        int left = used;
        for (ItemStack stack : supplied) {
            ItemStack kept = stack.copy();
            if (left > 0 && Goods.matches(kept, crop.seedSpec())) {
                int taken = Math.min(left, kept.getCount());
                kept.shrink(taken);
                left -= taken;
            }
            if (!kept.isEmpty()) {
                back.add(kept);
            }
        }
        return back;
    }
}
