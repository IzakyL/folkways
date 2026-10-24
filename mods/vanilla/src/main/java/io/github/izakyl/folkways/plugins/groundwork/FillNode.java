package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Recourse;
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

    private final FillPlan plan;
    private final long requestVersion;
    private boolean completed;

    FillNode(Chores.Chore chore, ItemSpec with, FillPlan plan) {
        super(GroundNode.declare(chore, BuildContent.trade(),
                Workload.Once.of(GroundworkContent.CELL_TICKS, BuildingPerks::constructionHaste))
            .needs(new Need(with, 1))
            .doing(GroundworkContent.FILLING, with).done(), chore);
        this.plan = plan;
        this.requestVersion = plan.requestVersion();
    }

    @Override
    public Optional<java.util.UUID> worker() {
        return plan.worker();
    }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        return completed || plan.requested(spec().focus().orElseThrow(), requestVersion) ? Optional.empty()
            : Optional.of(GroundRefusal.FILL_ACCESS_CHANGED);
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        return why == GroundRefusal.FILL_ACCESS_BLOCKED && plan.proving(spec().focus().orElseThrow())
            ? Recourse.waitFor(1) : Recourse.FAIL;
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job job) {
        List<ItemStack> supplied = who.supplied();
        BlockPos cell = job.cell();
        if (!plan.requested(cell, requestVersion)) {
            return refuse(GroundRefusal.FILL_ACCESS_CHANGED);
        }
        if (!Survey.open(level.getBlockState(cell))) {
            // A player or another operation occupying the target is a conflict, not our placement or
            // an invented material receipt. The project must reconcile it before publishing a new tail.
            return refuse(GroundRefusal.FILL_ACCESS_CHANGED);
        }
        for (ItemStack stack : supplied) {
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
                continue;
            }
            BlockState laid = item.getBlock().defaultBlockState();
            if (!laid.canSurvive(level, cell)) {
                return refuse(GroundRefusal.NOTHING_TO_LAY);
            }
            Optional<GroundRefusal> blocked = plan.permits(cell, laid, who.body());
            if (blocked.isPresent()) {
                return refuse(blocked.get());
            }
            // Let the certificate gate arrange a real resident's retreat first. The final atomic
            // collision check still runs immediately before the write, including players and newcomers.
            if (!level.isUnobstructed(laid, cell, CollisionContext.empty())) {
                return refuse(GroundRefusal.NOTHING_TO_LAY);
            }
            if (!level.setBlock(cell, laid, 3)) {
                return refuse(GroundRefusal.NOTHING_TO_LAY);
            }
            plan.committed(cell, laid, who.body());
            completed = true;
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
