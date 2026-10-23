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
            .needs(materials(chore))
            .doing(FarmingContent.PLANTING, chore.crop().seedSpec()).done(), chore);
        this.crop = chore.crop();
    }

    private static List<Need> materials(Chore chore) {
        List<Need> needs = new ArrayList<>();
        needs.add(new Need(chore.crop().seedSpec(), chore.job().cells().size()));
        chore.crop().behavior().ifPresent(b -> needs.addAll(b.plantingMaterials(chore.job().cells().size())));
        return List.copyOf(needs);
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job job) {
        BlockState sown = crop.sown();
        List<ItemStack> seeds = who.supplied().stream().map(ItemStack::copy)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
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
            // A crop sown into its frame, such as a vine on its post, is sown into what stands there.
            if (!standing.isAir() && !standing.canBeReplaced() && !crop.bare(level, base)) {
                continue;
            }
            ItemStack seed = seeds.stream().filter(stack -> Goods.matches(stack, crop.seedSpec()))
                .findFirst().orElse(ItemStack.EMPTY);
            if (seed.isEmpty() || !crop.behavior().map(b -> b.plant(level, who, base, seed, seeds))
                    .orElseGet(() -> crop.plant(level, who, base, seed))) {
                barren = true;
                continue;
            }
            CropCare.of(level).tend(base, level.getBlockState(base), who.effects().value(FarmingContent.GROWTH));
            heard.add(WorkNoise.placed(base, sown));
        }
        if (heard.isEmpty()) {
            return barren
                ? refuse(FarmRefusal.NOTHING_TO_WORK)
                : new Outcome.Done(List.of(), List.of(), Optional.empty(), who.supplied());
        }
        return new Outcome.Done(List.copyOf(heard), List.of(),
            Optional.of(new Xp(FarmingContent.trade(), heard.size())), seeds.stream().filter(stack -> !stack.isEmpty()).toList());
    }
}
