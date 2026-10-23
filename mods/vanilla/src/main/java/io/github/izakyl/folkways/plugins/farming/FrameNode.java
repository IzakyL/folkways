package io.github.izakyl.folkways.plugins.farming;

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

// One cell of the frame a plot is sown into, laid from the frame goods brought for it.
final class FrameNode extends FarmNode {

    private final Crop crop;

    FrameNode(Chore chore) {
        super(FarmNode.declare(chore, Workload.Once.of(FarmingContent.CELL_TICKS, FarmingContent::haste))
            .needs(chore.crop().behavior().flatMap(CropBehavior::frame).map(spec -> List.of(new Need(spec, 1)))
                .orElseGet(List::of))
            .focus(chore.job().cells().getFirst())
            .doing(FarmingContent.PLANTING, chore.crop().behavior().flatMap(CropBehavior::frame)
                .orElse(chore.crop().seedSpec())).done(), chore);
        this.crop = chore.crop();
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job job) {
        BlockPos at = job.cells().getFirst();
        if (!level.isLoaded(at) || crop.behavior().isEmpty()) {
            return refuse(FarmRefusal.NOTHING_TO_WORK);
        }
        if (!level.getBlockState(at).isAir()) {
            return Outcome.done();
        }
        List<ItemStack> supplied = new ArrayList<>(who.supplied().stream().map(ItemStack::copy).toList());
        if (!crop.behavior().get().lay(level, who, at, supplied)) {
            return refuse(FarmRefusal.NOTHING_TO_WORK);
        }
        return new Outcome.Done(List.of(WorkNoise.placed(at, level.getBlockState(at))), List.of(),
            Optional.of(new Xp(FarmingContent.trade(), 1)), supplied.stream().filter(stack -> !stack.isEmpty()).toList());
    }
}
