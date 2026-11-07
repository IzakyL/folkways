package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.work.api.ActionSpec;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.StructureCompat;
import io.github.izakyl.folkways.core.work.api.ToolUse;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

abstract class BuildAction implements WorkAction {

    Carving.Cache cache = new Carving.Cache(() -> { });

    private Carving.Quote quotation;
    private final BuildJob job;
    private final Spec spec;
    private Predicate<BlockPos> met = cell -> false;
    private Emptying emptying;
    private boolean onlyReplaceable;

    BuildAction(BuildJob job) {
        this.job = job;
        this.quotation = job.carving().orElse(null);
        this.spec = new Spec(job);
    }

    final class Spec implements ActionSpec {
        private final BuildJob job;

        Spec(BuildJob job) {
            this.job = job;
        }

        @Override
        public WorkSite site() {
            return new WorkSite.AtBlock(job.focus());
        }

        @Override
        public Stances stances() {
            return job.footings();
        }

        @Override
        public Workload workload() {
            return Workload.Once.of(job.laborTicks(), worker -> job.kind() == BuildJob.Kind.STRIP
                ? BuildContent.haste(worker) / BuildingPerks.momentum(worker,
                    worker.body().level().getBlockState(job.focus().block(worker.body().level())))
                : BuildingPerks.constructionHaste(worker));
        }

        @Override
        public List<Need> needs() {
            return quotation == null ? job.feed() : Carving.priced(quotation, cache).needs();
        }

        public List<Amount> leaves() {
            return quotation == null ? job.leaves() : Carving.priced(quotation, cache).leaves();
        }

        public int estimate() {
            return job.laborTicks();
        }

        @Override
        public List<ToolUse> tools() {
            return job.tools();
        }

        @Override
        public Optional<Vocation> vocation() {
            return Optional.of(BuildContent.trade());
        }

        @Override
        public WorkGesture gesture() {
            return WorkGesture.SWING;
        }

        @Override
        public Optional<BlockPos> focus() {
            return Optional.of(job.focus().cell());
        }

        @Override
        public ResourceLocation doing() {
            return job.doing();
        }

        @Override
        public Doing.Wares wares() {
            List<Doing.Ware> used = new ArrayList<>();
            needs().forEach(need -> Doings.item(need.spec())
                .ifPresent(item -> used.add(new Doing.Ware(item, need.count()))));
            if (!used.isEmpty()) {
                return new Doing.Wares(List.copyOf(used), List.of());
            }
            return Doings.item(job.block())
                .map(item -> new Doing.Wares(List.of(new Doing.Ware(item, 0L)), List.of()))
                .orElse(Doing.Wares.NONE);
        }

        @Override
        public Optional<String> about() {
            return Doings.about(job.block());
        }
    }

    static BuildAction of(BuildJob job, TemporaryScaffolds temporary) {
        return switch (job.kind()) {
            case LAY -> new LayAction(job);
            case CARVE -> new CarveAction(job);
            case STRIP -> new StripAction(job);
            case DRAIN -> new DrainAction(job);
            case RAISE, LOWER -> new ScaffoldAction(job, temporary);
            case JOIN -> new JoinAction(job);
            case SOAK -> new SoakAction(job);
        };
    }

    final void requote(ServerLevel level) {
        if (job.kind() != BuildJob.Kind.CARVE) {
            return;
        }
        var quote = Carving.quote(level, remaining(level, job.after(), false), pos -> job.data().entrySet().stream()
            .filter(entry -> entry.getKey().block(level).equals(pos)).map(Map.Entry::getValue).findFirst()
            .orElse(BlockData.EMPTY), cache);
        quotation = quote;
    }

    final Carving.Quote quotation() {
        return quotation;
    }

    final BuildAction keeping(Predicate<BlockPos> met) {
        this.met = met;
        return this;
    }

    final BuildAction onlyReplaceable() {
        onlyReplaceable = true;
        return this;
    }

    final BuildAction emptying(Emptying emptying) {
        this.emptying = emptying;
        return this;
    }

    final Optional<BuildRefusal> obstruction(ServerLevel level) {
        if (finished(level) || job.kind() != BuildJob.Kind.STRIP && job.kind() != BuildJob.Kind.LAY
            && job.kind() != BuildJob.Kind.DRAIN && job.kind() != BuildJob.Kind.CARVE) {
            return Optional.empty();
        }
        Map<BlockPos, BlockState> cells = remaining(level, job.before(), true);
        // A late replacement may have put a container in a cell that was empty when the plan was laid.
        remaining(level, job.after(), true).forEach(cells::putIfAbsent);
        return emptying == null ? Emptying.check(level, cells) : emptying.before(level, cells);
    }

    @Override
    public final Spec spec() {
        return spec;
    }

    final boolean ready(ServerLevel level) {
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
            boolean satisfied = BuildPlanner.satisfied(level.getBlockState(pos), cell.getValue());
            var handler = StructureCompat.of(cell.getValue());
            if (satisfied && handler.isPresent() && !met.test(pos)) {
                satisfied = handler.get().matches(level.getBlockEntity(pos),
                    job.data().getOrDefault(cell.getKey(), BlockData.EMPTY).payload(cell.getValue()));
            }
            if (!met.test(pos) && !satisfied) {
                return false;
            }
        }
        return true;
    }
}
