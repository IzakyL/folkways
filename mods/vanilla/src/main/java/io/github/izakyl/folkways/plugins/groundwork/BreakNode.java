package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.plugins.build.BuildContent;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

// One block taken out: by a builder clearing a box, with the tool the block wants, or by a farmer pulling a weed.
final class BreakNode extends GroundNode {

    enum Way {
        CLEAR,
        WEED;

        Vocation by() {
            return this == CLEAR ? BuildContent.trade() : FarmingContent.trade();
        }

        ResourceLocation doing() {
            return this == CLEAR ? GroundworkContent.CLEARING : GroundworkContent.WEEDING;
        }

        Workload.Haste haste() {
            return this == CLEAR ? BuildContent::haste : FarmingContent::haste;
        }
    }

    private final Way way;
    // What the work takes out, read again when it gets there: a weed may have given way to something else.
    private final Predicate<BlockState> taken;
    // What the zone leaves standing, read again when the block is taken: it may have joined the colony since.
    private final BiPredicate<BlockPos, BlockState> spared;

    BreakNode(Chores.Chore chore, Way way, BlockState seen, Predicate<BlockState> taken,
            BiPredicate<BlockPos, BlockState> spared) {
        super(declared(chore, way, seen), chore);
        this.way = way;
        this.taken = taken;
        this.spared = spared;
    }

    private static NodeSpec declared(Chores.Chore chore, Way way,
            BlockState seen) {
        var spec = GroundNode.declare(chore, way.by(), Workload.Once.of(ticksFor(way, seen), way.haste()))
            .leaves(Leaves.ofBlocks(List.of(seen)));
        if (way == Way.CLEAR && seen.requiresCorrectToolForDrops()) {
            spec.tools(new ToolUse(new ToolNeed.SuitableFor(seen), 1));
        }
        return spec.doing(way.doing(), seen.getBlock()).done();
    }

    private static int ticksFor(Way way, BlockState seen) {
        if (way == Way.WEED) {
            return GroundworkContent.CELL_TICKS;
        }
        float hardness = seen.getBlock().defaultDestroyTime();
        return Math.min(GroundworkContent.MOST_BREAK_TICKS, GroundworkContent.BREAK_TICKS
            + Math.round(Math.max(0.0F, hardness) * GroundworkContent.TICKS_PER_HARDNESS));
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job job) {
        BlockPos cell = job.cell();
        BlockState state = level.getBlockState(cell);
        if (state.isAir() || !taken.test(state)) {
            return Outcome.done();
        }
        if (state.getDestroySpeed(level, cell) < 0.0F || spared.test(cell, state)) {
            return refuse(GroundRefusal.NOTHING_TO_WORK);
        }
        ItemStack tool = way == Way.CLEAR ? who.held(new ToolNeed.SuitableFor(state)) : ItemStack.EMPTY;
        List<ItemStack> drops = take(level, who.body(), cell, state, tool);
        return new Outcome.Done(List.of(WorkNoise.broke(cell, state)), drops,
            Optional.of(new Xp(way.by(), 1)));
    }
}
