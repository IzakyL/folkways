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
import io.github.izakyl.folkways.plugins.build.BuildRefusal;
import io.github.izakyl.folkways.plugins.build.BuildingPerks;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import java.util.ArrayList;
import java.util.Collections;
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

        Workload.Haste haste(Chores.Chore chore) {
            return this == CLEAR ? worker -> BuildContent.haste(worker) / BuildingPerks.momentum(worker,
                worker.body().level().getBlockState(chore.job().cell())) : FarmingContent::haste;
        }
    }

    private final Way way;
    private final List<BlockPos> cells;
    // What the work takes out, read again when it gets there: a weed may have given way to something else.
    private final Predicate<BlockState> taken;
    // What the zone leaves standing, read again when the block is taken: it may have joined the colony since.
    private final BiPredicate<BlockPos, BlockState> spared;

    BreakNode(Chores.Chore chore, Way way, BlockState seen, Predicate<BlockState> taken,
            BiPredicate<BlockPos, BlockState> spared) {
        super(declared(chore, way, seen), chore);
        this.way = way;
        this.cells = chore.job().cells();
        this.taken = taken;
        this.spared = spared;
    }

    private static NodeSpec declared(Chores.Chore chore, Way way,
            BlockState seen) {
        var spec = GroundNode.declare(chore, way.by(), Workload.Once.of(ticksFor(way, seen), way.haste(chore)))
            .leaves(Leaves.ofBlocks(Collections.nCopies(chore.job().cells().size(), seen)));
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
    public boolean ready(ServerLevel level) {
        return super.ready(level) && cells.stream().allMatch(level::isLoaded)
            && (way != Way.CLEAR || !new ClearOrder(level).blocked(cells));
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
        if (way == Way.CLEAR) {
            for (BlockPos part : cells) {
                if (!level.isLoaded(part)) {
                    return Outcome.failed(BuildRefusal.CONTESTED);
                }
                BlockState standing = level.getBlockState(part);
                if (!cells.containsAll(ClearOrder.parts(level, part)) || !standing.isAir()
                        && (!taken.test(standing) || standing.getDestroySpeed(level, part) < 0.0F
                            || spared.test(part, standing))) {
                    return Outcome.failed(BuildRefusal.CONTESTED);
                }
            }
            if (new ClearOrder(level).blocked(cells)) {
                return Outcome.failed(BuildRefusal.CONTESTED);
            }
        }
        ItemStack tool = way == Way.CLEAR
            ? BuildingPerks.fortunate(level, who, who.held(new ToolNeed.SuitableFor(state))) : ItemStack.EMPTY;
        List<ItemStack> drops = new ArrayList<>(way == Way.CLEAR ? take(level, who.body(), cells, tool)
            : take(level, who.body(), cell, state, tool));
        if (way == Way.CLEAR && level.getBlockState(cell).isAir()) {
            drops.addAll(BuildingPerks.discoveries(level, who, cell, state));
            BuildingPerks.mined(who, state);
        }
        return new Outcome.Done(List.of(WorkNoise.broke(cell, state)), drops,
            Optional.of(new Xp(way.by(), 1)));
    }
}
