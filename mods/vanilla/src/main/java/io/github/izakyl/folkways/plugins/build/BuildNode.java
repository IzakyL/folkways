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
import java.util.function.Predicate;
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
    private Predicate<BlockPos> met = cell -> false;
    private Emptying emptying;
    private boolean onlyReplaceable;

    BuildNode(BuildJob job, Ends ends) {
        this.job = job;
        this.ends = ends;
        this.spec = NodeSpec.of(UUID.randomUUID(), BuildContent.ID, new WorkSite.AtBlock(job.focus()),
                job.footings(), Workload.Once.of(job.laborTicks(), worker -> job.kind() == BuildJob.Kind.STRIP
                    ? BuildContent.haste(worker) / BuildingPerks.momentum(worker,
                        worker.body().level().getBlockState(job.focus().block(worker.body().level())))
                    : BuildingPerks.constructionHaste(worker)))
            .needs(job.feed())
            .leaves(job.leaves())
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
            case STRIP -> new StripNode(job, ends);
            case DRAIN -> new DrainNode(job, ends);
            case RAISE, LOWER -> new ScaffoldNode(job, ends, temporary);
            case JOIN -> new JoinNode(job, ends);
            case SOAK -> new SoakNode(job, ends);
        };
    }

    final BuildNode keeping(Predicate<BlockPos> met) {
        this.met = met;
        return this;
    }

    final BuildNode onlyReplaceable() {
        onlyReplaceable = true;
        return this;
    }

    final BuildNode emptying(Emptying emptying) {
        this.emptying = emptying;
        return this;
    }

    final Optional<BuildRefusal> obstruction(ServerLevel level) {
        if (finished(level) || job.kind() != BuildJob.Kind.STRIP && job.kind() != BuildJob.Kind.LAY
            && job.kind() != BuildJob.Kind.DRAIN) {
            return Optional.empty();
        }
        Map<BlockPos, BlockState> cells = remaining(level, job.before(), true);
        // A late replacement may have put a container in a cell that was empty when the plan was laid.
        remaining(level, job.after(), true).forEach(cells::putIfAbsent);
        return emptying == null ? Emptying.check(level, cells) : emptying.before(level, cells);
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
        if (onlyReplaceable && job.after().entrySet().stream().anyMatch(entry -> {
            BlockPos cell = entry.getKey().block(level);
            return !level.getBlockState(cell).canBeReplaced() || !level.getFluidState(cell).isEmpty()
                || !entry.getValue().canSurvive(level, cell);
        })) {
            return refuse(BuildRefusal.NO_WAY_TO_BUILD);
        }
        Optional<BuildRefusal> blocked = obstruction(level);
        if (blocked.isPresent()) {
            return refuse(blocked.get());
        }
        Outcome outcome = work(level, who);
        if (emptying != null) {
            emptying.removed(level);
        }
        return outcome;
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

    /** Execution must neither inspect nor replace a target cell whose completion has been latched. */
    final Map<BlockPos, BlockState> remaining(ServerLevel level, Map<WorldPos, BlockState> cells, boolean readWorld) {
        Map<BlockPos, BlockState> remaining = new LinkedHashMap<>();
        cells.forEach((cell, state) -> {
            BlockPos pos = cell.block(level);
            if (!met.test(pos)) {
                remaining.put(pos, readWorld ? level.getBlockState(pos) : state);
            }
        });
        return remaining;
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
            if (!met.test(pos) && !BuildPlanner.satisfied(level.getBlockState(pos), cell.getValue())) {
                return false;
            }
        }
        return true;
    }
}
