package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

final class PlantNode extends FarmNode {

    private final Crop crop;

    PlantNode(Chore chore) {
        super(FarmNode.declare(chore, Workload.Once.of(FarmingContent.CELL_TICKS,
                FarmingContent::haste))
            .needs(new Need(chore.batch().crop().seedSpec(), 1))
            .doing(FarmingContent.PLANTING, chore.batch().crop().seedSpec()).done(), chore);
        this.crop = chore.batch().crop();
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job job) {
        BlockPos base = job.cell().above();
        if (!level.isLoaded(base)) {
            return refuse(FarmRefusal.NOTHING_TO_WORK);
        }
        BlockState standing = level.getBlockState(base);
        BlockState sown = crop.sown();
        if (!standing.isAir() && !standing.canBeReplaced()) {
            return new Outcome.Done(List.of(), List.of(), Optional.empty(), who.supplied());
        }
        if (!sown.canSurvive(level, base) || !crop.roomAt(level, base)) {
            return refuse(FarmRefusal.NOTHING_TO_WORK);
        }
        level.setBlock(base, sown, 3);
        return new Outcome.Done(List.of(WorkNoise.placed(base, sown)), List.of(),
            Optional.of(new Xp(FarmingContent.trade(), 1)));
    }
}
