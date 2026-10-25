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
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

// A farmer pulls a weed or clears a fallen log with its decorations.
final class BreakNode extends GroundNode {

    enum Way {
        FALLEN,
        WEED;

        Vocation by() {
            return FarmingContent.trade();
        }

        ResourceLocation doing() {
            return this == FALLEN ? GroundworkContent.FELLING : GroundworkContent.WEEDING;
        }

        Workload.Haste haste(Chores.Chore chore) {
            return FarmingContent::haste;
        }
    }

    private final Way way;
    // What the work takes out, read again when it gets there: a weed may have given way to something else.
    private final Predicate<BlockState> taken;
    // What the zone leaves standing, read again when the block is taken: it may have joined the colony since.
    private final BiPredicate<BlockPos, BlockState> spared;

    BreakNode(ServerLevel level, Chores.Chore chore, Way way, BlockState seen, Predicate<BlockState> taken,
            BiPredicate<BlockPos, BlockState> spared) {
        super(declared(level, chore, way, seen), chore);
        this.way = way;
        this.taken = taken;
        this.spared = spared;
    }

    private static NodeSpec declared(ServerLevel level, Chores.Chore chore, Way way,
            BlockState seen) {
        List<BlockState> blocks = new ArrayList<>();
        blocks.add(seen);
        chore.job().canopy().forEach(cell -> blocks.add(level.getBlockState(cell)));
        var spec = GroundNode.declare(chore, way.by(), Workload.Once.of(ticksFor(way, seen), way.haste(chore)))
            .leaves(Leaves.ofBlocks(blocks));
        if (way != Way.WEED && seen.requiresCorrectToolForDrops()) {
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
        for (BlockPos decoration : job.canopy()) {
            if (!level.isLoaded(decoration) || spared.test(decoration, level.getBlockState(decoration))) {
                return refuse(GroundRefusal.NOTHING_TO_WORK);
            }
        }
        ItemStack tool = way == Way.WEED ? ItemStack.EMPTY : who.held(new ToolNeed.SuitableFor(state));
        List<ItemStack> drops = new ArrayList<>();
        List<WorkNoise> noises = new ArrayList<>();
        // Collect decorations before removing their support, so neighbor updates cannot discard their loot.
        for (BlockPos decoration : job.canopy()) {
            BlockState plant = level.getBlockState(decoration);
            if (FallenLogs.decoration(plant)) {
                drops.addAll(take(level, who.body(), decoration, plant, tool));
                noises.add(WorkNoise.broke(decoration, plant));
            }
        }
        drops.addAll(take(level, who.body(), cell, state, tool));
        noises.add(WorkNoise.broke(cell, state));
        return new Outcome.Done(noises, drops,
            Optional.of(new Xp(way.by(), 1)));
    }
}
