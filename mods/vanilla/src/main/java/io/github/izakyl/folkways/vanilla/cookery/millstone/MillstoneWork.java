package io.github.izakyl.folkways.vanilla.cookery.millstone;

import com.github.ysbbbbbb.kaleidoscopecookery.crafting.container.SimpleInput;
import io.github.izakyl.folkways.core.facility.api.ProcessLoop;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.Readiness;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.support.Leaves;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.cookery.CookeryContent;
import io.github.izakyl.folkways.vanilla.cookery.CookeryRefusal;
import io.github.izakyl.folkways.vanilla.cookery.KitchenBookings;
import io.github.izakyl.folkways.vanilla.cookery.KitchenClosure;
import io.github.izakyl.folkways.core.facility.api.ProcessHolding;
import io.github.izakyl.folkways.vanilla.cookery.KitchenWork;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class MillstoneWork implements KitchenWork {
    private enum Phase { LOAD, DRIVE, TAKE, DONE }
    private final UUID colony;
    private final UUID batch;
    private final WorldPos at;
    private final Stances stances;
    private final MillstoneStation.Job job;
    private final int count;
    private final KitchenBookings bookings;
    private final long generation;
    private final Runnable changed;
    private final NodeSpec spec;
    private final Grown recoveryGoal;
    private final boolean recovering;
    private Hold recoveryHold;
    // Mill in, turn the stone, take out; the loop is where the work has got to.
    private final ProcessLoop loop;
    private boolean loaded;
    private final KitchenClosure closure;

    MillstoneWork(UUID colony, WorldPos at, Stances stances, MillstoneStation.Job job, int count,
                  KitchenBookings bookings, Runnable changed, MillstoneBatch.Batch previous) {
        this.colony = colony; this.at = at; this.stances = stances; this.job = job; this.count = count;
        this.bookings = bookings; this.generation = bookings.generation(at); this.changed = changed;
        this.batch = previous == null ? UUID.randomUUID() : previous.id();
        this.recovering = previous != null;
        this.loaded = recovering;
        this.loop = new ProcessLoop(new Steps(), recovering);
        this.spec = NodeSpec.of(batch, CookeryContent.ID, new WorkSite.AtBlock(at), stances, new Workload.Custom())
            .needs(recovering ? List.of() : List.of(new Need(job.input(), count, true)))
            .gives(job.amounts(count, false)).leaves(job.amounts(count, true)).effort(WorkEffort.NONE)
            .vocation(WaresContent.trade()).gesture(WorkGesture.SWING).focus(at.cell()).estimate(560)
            .doing(CookeryContent.PREPARING).done();
        recoveryGoal = recovering ? Grown.of(this) : null;
        closure = new KitchenClosure(how -> {
            if (recovering) {
                MillstoneStation.ended(colony, at, this);
            }
            if (recoveryHold != null) {
                recoveryHold.release(how);
                recoveryHold = null;
            }
        }, changed);
    }
    Grown recoveryGoal() { return recoveryGoal;
    }

    @Override
    public boolean producesBeforeSettlement() {
        return true; }
    public NodeSpec spec() { return spec; }
    @Override
    public List<Amount> planningLeaves(List<Amount> promised) {
        return Leaves.unpromised(spec.leaves(), promised);
    }
    public Optional<RefusalKind> planned(ServerLevel level) {
        if (!recovering) { return Optional.empty(); }
        if (generation != bookings.generation(at) || !bookings.free(at)) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        recoveryHold = bookings.hold(at, id(), spec().estimate());
        Optional<RefusalKind> refused = recoveryHold.take();
        if (refused.isEmpty()) { MillstoneStation.keep(colony, at, recoveryGoal, generation); }
        return refused;
    }
    @Override
    public Optional<RefusalKind> planned(ServerLevel level, Consumer<RefusalKind> fail) {
        Optional<RefusalKind> refused = planned(level);
        if (refused.isEmpty()) {
            if (closure.closed() || bookings.cancelled(at, id(), generation)) {
                return Optional.of(CookeryRefusal.STATION_CHANGED);
            }
            closure.onWithdrawal(bookings, at, () -> fail.accept(CookeryRefusal.STATION_CHANGED));
        }
        return refused;
    }

    public Readiness readiness(ServerLevel level) {
        return Readiness.of(bookings.cancelled(at, id(), generation) || bookings.first(at, id()) && MillstoneStation.mill(level, at).isPresent());
    }
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (closure.closed()) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (bookings.cancelled(at, id(), generation)) { return Optional.of(CookeryRefusal.STATION_CHANGED); }
        var mill = MillstoneStation.mill(level, at);
        if (mill.isEmpty()) { return Optional.of(CookeryRefusal.NOT_A_STATION); }
        if (loaded && phase() != Phase.DONE && MillstoneBatch.read(mill.get(), level)
                .filter(found -> found.id().equals(batch) && found.colony().equals(colony)).isEmpty()) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (!loaded && phase() == Phase.LOAD && bookings.first(at, id()) && (mill.get().hasEntity() && !MillstoneDrive.external(level, mill.get())
                || !mill.get().getInput().isEmpty() || !mill.get().isOutputEmpty())) {
            return Optional.of(CookeryRefusal.STATION_BUSY);
        }
        return Optional.empty();
    }

    @Override
    public WorkHolding acquire(HoldingContext context) {
        return new MillstoneHolding();
    }

    private final class MillstoneHolding extends ProcessHolding {

        @Override
        public void requestSoftPreempt(HoldingContext context, SoftPreempt request) {
            MillstoneDrive.interrupt(context.worker().body());
            super.requestSoftPreempt(context, request);
        }

        @Override
        public void hardRelease(HoldingContext context, Disposition result, PreemptReason reason) {
            MillstoneDrive.interrupt(context.worker().body());
            super.hardRelease(context, result, reason);
        }

        MillstoneHolding() {
            super(loop::next, loop::over, MillstoneWork.this::commit, loop::fail);
        }

        @Override
        protected HoldingStep stepCompleted(HoldingContext context, WorkAction operation, HoldingStep step) {
            MillstoneWork.this.completed(context.level(), operation, step instanceof HoldingStep.Failed failure
                ? new Ending.Failed(id(), failure.why()) : Ending.DONE);
            return new HoldingStep.Running();
        }
    }

    private Phase phase() {
        if (loop.isFinished()) { return Phase.DONE; }
        return !loop.isLoaded() ? Phase.LOAD : !loop.isStarted() ? Phase.DRIVE : Phase.TAKE;
    }

    // Driving the stone is the loop's start step; once the stone stops, the output is there to take.
    private final class Steps implements ProcessLoop.Steps {
        public Optional<RefusalKind> refusal(ServerLevel level) { return MillstoneWork.this.refusal(level); }
        public boolean turn(ServerLevel level) { return bookings.first(at, id()); }
        public WorkAction load(ServerLevel level, Worker who) { return new Action(level, Phase.LOAD); }
        public boolean starts() { return true; }
        public WorkAction start(ServerLevel level, Worker who) { return new Drive(); }
        public boolean outputReady(ServerLevel level) { return true; }
        public WorkAction take(ServerLevel level, Worker who) { return new Action(level, Phase.TAKE); }
    }

    // Each step moves the loop on only once it is done.
    private void completed(ServerLevel level, WorkAction piece, Ending how) {
        if (how instanceof Ending.Failed failure) { loop.fail(failure.why()); }
        if (how instanceof Ending.Done) {
            switch (phase()) {
                case LOAD -> loop.loaded();
                case DRIVE -> loop.started();
                default -> loop.finished();
            }
        }
    }
    public Outcome commit(ServerLevel level, Worker who) {
        return loop.isFinished() && !bookings.cancelled(at, id(), generation) ? Outcome.done()
            : Outcome.failed(loop.failure().orElse(CookeryRefusal.STATION_CHANGED));
    }

    @Override
    public KitchenClosure closure() {
        return closure;
    }

    private NodeSpec actionSpec(ServerLevel level, UUID id, Workload workload, boolean load) {
        Stances working = load ? stances : new MillstoneStation().stances(level, at).orElse(stances);
        return NodeSpec.of(id, CookeryContent.ID, new WorkSite.AtBlock(at), working, workload)
            .needs(load ? List.of(new Need(job.input(), count, true)) : List.of())
            .gives(load ? List.of() : job.amounts(count, false)).leaves(load ? List.of() : job.amounts(count, true))
            .vocation(WaresContent.trade()).gesture(WorkGesture.SWING).focus(at.cell())
            .doing(CookeryContent.PREPARING).done();
    }
    private class Action implements WorkAction {
        final Phase kind;
        private final NodeSpec spec;
        Action(ServerLevel level, Phase kind) {
            this.kind = kind;
            spec = actionSpec(level, UUID.randomUUID(), Workload.Once.of(20), kind == Phase.LOAD);
        }
        public NodeSpec spec() { return spec; }
        public Optional<RefusalKind> refusal(ServerLevel level) { return MillstoneWork.this.refusal(level); }
        public Outcome commit(ServerLevel level, Worker who) {
            if (refusal(level).isPresent() || !MillstoneDrive.licensed(who.body())
                    || !bookings.first(at, MillstoneWork.this.id())) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            var mill = MillstoneStation.mill(level, at).orElseThrow();
            if (kind == Phase.LOAD) {
                ItemStack input = who.supplied().stream().filter(stack -> Goods.matches(stack, job.input()))
                    .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
                int supplied = who.supplied().stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, input))
                    .mapToInt(ItemStack::getCount).sum();
                if (supplied < count) { return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH); }
                input.setCount(count);
                var recipe = mill.matchRecipe(new SimpleInput(List.of(input)), level);
                if (FolkwaysVanillaConfig.refusedRecipes().contains(job.recipe()) || recipe.isEmpty()
                        || !recipe.get().id().equals(job.recipe())) {
                    return Outcome.failed(CookeryRefusal.RECIPE_CHANGED);
                }
                if (!MillstoneDrive.external(level, mill)
                        && !MillstoneDrive.bind(colony, batch, level, at, who.body(), bookings, MillstoneWork.this.id(), generation)) {
                    return Outcome.failed(CookeryRefusal.STATION_BUSY);
                }
                try {
                    if (!mill.onPutItem(level, input)) {
                        MillstoneDrive.interrupt(who.body());
                        return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
                    }
                } catch (RuntimeException | LinkageError broken) {
                    if (mill.getInput().getCount() != count || !Goods.matches(mill.getInput(), job.input())) {
                        MillstoneDrive.interrupt(who.body());
                        throw broken;
                    }
                    // The real mill already owns the ingredients. A failed outcome would refund a second copy.
                    loop.fail(CookeryRefusal.STATION_CHANGED);
                    MillstoneDrive.interrupt(who.body());
                }
                loaded = true;
                MillstoneBatch.remember(mill, level, new MillstoneBatch.Batch(colony, batch, job.recipe(), false));
                changed.run();
                return Outcome.done();
            }
            var owned = MillstoneBatch.read(mill, level).orElseThrow();
            if (!owned.ready() || mill.hasEntity() && !MillstoneDrive.external(level, mill) || MillstoneDrive.exiting(who.body())) {
                return Outcome.failed(CookeryRefusal.STATION_BUSY);
            }
            var before = mill.saveWithoutMetadata(level.registryAccess());
            List<ItemStack> made = new ArrayList<>();
            try {
                for (int slot = 0; slot < mill.getOutputs().getSlots(); slot++) {
                    ItemStack actual = mill.getOutputs().extractItem(slot, Integer.MAX_VALUE, false);
                    if (!actual.isEmpty()) { made.add(actual); }
                }
                mill.resetWhenTakeout();
            } catch (RuntimeException | LinkageError broken) {
                mill.loadWithComponents(before, level.registryAccess());
                mill.setChanged();
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            loop.finished();
            MillstoneBatch.clear(mill);
            changed.run();
            return new Outcome.Done(List.of(), made, Optional.empty());
        }
    }
    private final class Drive implements WorkAction, Workload.Continuous {
        private final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(at),
            Stances.WHEREVER, this).vocation(WaresContent.trade()).doing(CookeryContent.PREPARING).done();
        public NodeSpec spec() { return spec; }
        public Optional<RefusalKind> refusal(ServerLevel level) { return MillstoneWork.this.refusal(level); }
        public boolean holds(ServerLevel level, Worker who) {
            if (refusal(level).isPresent()) { MillstoneDrive.interrupt(who.body()); return false; }
            var mill = MillstoneStation.mill(level, at).orElseThrow();
            if (!MillstoneDrive.licensed(who.body())) {
                if (mill.isBoundTo(who.body())) { MillstoneDrive.interrupt(who.body()); }
                if (MillstoneDrive.exiting(who.body()) && !MillstoneDrive.leave(level, who.body())) { return true; }
                loop.fail(CookeryRefusal.STATION_CHANGED);
                return false;
            }
            var owned = MillstoneBatch.read(mill, level).orElseThrow();
            if (owned.ready()) { return !MillstoneDrive.leave(level, who.body()); }
            if (MillstoneDrive.exiting(who.body()) && !MillstoneDrive.leave(level, who.body())) { return true; }
            if (!mill.hasEntity() && !MillstoneDrive.bind(colony, batch, level, at, who.body(), bookings, MillstoneWork.this.id(), generation)) {
                loop.fail(CookeryRefusal.STATION_BUSY);
                return false;
            }
            return true;
        }
        public Outcome commit(ServerLevel level, Worker who) {
            return loop.failure().isEmpty() && MillstoneDrive.licensed(who.body()) && refusal(level).isEmpty() && MillstoneStation.mill(level, at)
                    .flatMap(mill -> MillstoneBatch.read(mill, level)).filter(MillstoneBatch.Batch::ready).isPresent()
                    && !MillstoneDrive.exiting(who.body()) ? Outcome.done()
                : Outcome.failed(loop.failure().orElse(CookeryRefusal.STATION_CHANGED));
        }
    }
}
