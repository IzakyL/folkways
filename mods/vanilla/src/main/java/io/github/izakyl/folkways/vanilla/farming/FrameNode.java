package io.github.izakyl.folkways.vanilla.farming;

import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.WorkNoise;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.api.Xp;
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
