package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.plugins.build.BuildContent;
import io.github.izakyl.folkways.plugins.build.BuildingPerks;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

// One cell of a fill zone laid with a block of what the zone is filled with, brought from the colony's stores.
final class FillNode extends GroundNode {

    FillNode(Chores.Chore chore, ItemSpec with) {
        super(GroundNode.declare(chore, BuildContent.trade(),
                Workload.Once.of(GroundworkContent.CELL_TICKS, BuildingPerks::constructionHaste))
            .needs(new Need(with, 1).replacing())
            .doing(GroundworkContent.FILLING, with).done(), chore);
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job job) {
        List<ItemStack> supplied = who.supplied();
        BlockPos cell = job.cell();
        if (!Survey.open(level.getBlockState(cell))) {
            return new Outcome.Done(List.of(), List.of(), Optional.empty(), supplied);
        }
        for (ItemStack stack : supplied) {
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
                continue;
            }
            BlockState laid = item.getBlock().defaultBlockState();
            // Nobody is walled up in it: a body standing in the cell waits for the next sweep.
            if (!laid.canSurvive(level, cell) || !level.isUnobstructed(laid, cell, CollisionContext.empty())) {
                return refuse(GroundRefusal.NOTHING_TO_LAY);
            }
            level.setBlock(cell, laid, 3);
            BuildingPerks.interrupt(who);
            return new Outcome.Done(List.of(WorkNoise.placed(cell, laid)), List.of(),
                Optional.of(new Xp(BuildContent.trade(), 1)), unused(supplied, stack));
        }
        return refuse(GroundRefusal.NOTHING_TO_LAY);
    }

    // What was handed over, less the one block laid from this stack.
    private static List<ItemStack> unused(List<ItemStack> supplied, ItemStack laidFrom) {
        List<ItemStack> back = new ArrayList<>(supplied.size());
        for (ItemStack stack : supplied) {
            ItemStack kept = stack.copy();
            if (stack == laidFrom) {
                kept.shrink(1);
            }
            if (!kept.isEmpty()) {
                back.add(kept);
            }
        }
        return back;
    }
}
