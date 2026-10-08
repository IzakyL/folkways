package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Unmet;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

abstract class BuildNode implements Node {

    @FunctionalInterface
    interface Ends {

        void ended(Node node, Ending how);
    }

    private static final int OCCUPIED_WAIT_TICKS = 20;

    private static final int RETRY_TICKS = 100;

    private final BuildJob job;
    private final Ends ends;
    private final NodeSpec spec;

    BuildNode(BuildJob job, Ends ends) {
        this.job = job;
        this.ends = ends;
        this.spec = NodeSpec.of(UUID.randomUUID(), BuildContent.ID, new WorkSite.AtBlock(job.focus()),
                job.footings(), Workload.Once.of(job.laborTicks(), BuildContent::haste))
            .needs(job.feed())
            .tools(job.tools())
            .vocation(BuildContent.trade())
            .gesture(WorkGesture.SWING)
            .focus(job.focus().cell())
            .doing(job.doing(), job.block())
            .done();
    }

    static BuildNode of(BuildJob job, Ends ends, TemporaryScaffolds temporary) {
        return switch (job.kind()) {
            case LAY -> new LayNode(job, ends);
            case POUR -> new PourNode(job, ends);
            case STRIP -> new StripNode(job, ends);
            case DRAIN -> new DrainNode(job, ends);
            case RAISE, LOWER -> new ScaffoldNode(job, ends, temporary);
            case JOIN -> new JoinNode(job, ends);
        };
    }

    @Override
    public final NodeSpec spec() {
        return spec;
    }

    @Override
    public final boolean ready(ServerLevel level) {
        return loaded(level);
    }

    @Override
    public final Outcome commit(ServerLevel level, Worker who) {
        if (!loaded(level)) {
            return refuse(BuildRefusal.CELL_NOT_LOADED);
        }
        if (finished(level)) {
            return new Outcome.Done(List.of(), List.of(), Optional.empty(), who.supplied());
        }
        return work(level, who);
    }

    // The plan a build goes up by is laid once and kept: a piece that cannot go in yet waits and is tried again,
    // someone standing where a block goes sooner than the rest. Only a block no one could ever break gives it up.
    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        if (why == BuildRefusal.NO_WAY_TO_BUILD) {
            return Recourse.FAIL;
        }
        return Recourse.waitFor(why == BuildRefusal.OCCUPIED ? OCCUPIED_WAIT_TICKS : RETRY_TICKS);
    }

    // A piece goes in after the pieces before it: with one of them undone, it waits for that one to be asked again.
    // One withdrawn was met some other way, or its order given up: either way nothing is left to wait for.
    @Override
    public Unmet unmet(UUID before, Ending how) {
        return how instanceof Ending.Revoked ? Unmet.GO_ON : Unmet.WAIT;
    }

    @Override
    public final void ended(ServerLevel level, Ending how) {
        ends.ended(this, how);
    }

    abstract Outcome work(ServerLevel level, Worker who);

    final BuildJob job() {
        return job;
    }

    final BlockPos cell(ServerLevel level) {
        return job.focus().block(level);
    }

    static Map<BlockPos, BlockState> blocks(ServerLevel level, Map<WorldPos, BlockState> cells) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        cells.forEach((cell, state) -> blocks.put(cell.block(level), state));
        return blocks;
    }

    // What stands in the job's cells now, whatever the plan saw there: the work takes the world as it finds it.
    static Map<BlockPos, BlockState> standing(ServerLevel level, Map<WorldPos, BlockState> cells) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        cells.keySet().forEach(cell -> {
            BlockPos at = cell.block(level);
            blocks.put(at, level.getBlockState(at));
        });
        return blocks;
    }

    // What the job feeds on, handed over by the core before the work starts.
    final List<ItemStack> spend(Worker who) {
        return who.supplied();
    }

    final Outcome refuse(BuildRefusal why) {
        return Outcome.failed(why);
    }

    private boolean loaded(ServerLevel level) {
        for (WorldPos cell : job.before().keySet()) {
            if (!cell.in(level) || !level.isLoaded(cell.block(level))) {
                return false;
            }
        }
        return true;
    }

    boolean finished(ServerLevel level) {
        for (Map.Entry<WorldPos, BlockState> cell : job.after().entrySet()) {
            BlockPos pos = cell.getKey().block(level);
            if (!BuildPlanner.satisfied(level.getBlockState(pos), cell.getValue())) {
                return false;
            }
        }
        return true;
    }
}
