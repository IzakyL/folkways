package io.github.izakyl.folkways.vanilla.cookery.stockpot;

import io.github.izakyl.folkways.core.facility.api.ProcessHolding;
import io.github.izakyl.folkways.core.facility.api.ProcessLoop;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.support.Leaves;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.cookery.CookeryContent;
import io.github.izakyl.folkways.vanilla.cookery.CookeryRefusal;
import io.github.izakyl.folkways.vanilla.cookery.KitchenBookings;
import io.github.izakyl.folkways.vanilla.cookery.KitchenClosure;
import io.github.izakyl.folkways.vanilla.cookery.KitchenWork;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

// One production offer has one completion and one input budget. Its pieces use the real pot batch by batch;
// serving a first batch must not promise all later batches to the downstream consumer yet.
final class StockpotWork implements KitchenWork, ProcessLoop.Steps {

    private final NodeSpec spec;
    private final UUID colony;
    private final long generation;
    private final WorldPos at;
    private final Stances stances;
    private final StockpotPlan plan;
    private final int runs;
    private final boolean hasLid;
    private final KitchenBookings bookings;
    private final Runnable changed;
    private StockpotLoad loading;
    private StockpotTake taking;
    private int served;
    private ProcessLoop loop;
    private final KitchenClosure closure;

    StockpotWork(UUID colony, WorldPos at, Stances stances, StockpotPlan plan, int runs,
                 boolean hasLid, KitchenBookings bookings, Runnable changed) {
        this.colony = colony;
        this.generation = bookings.generation(at);
        this.at = at;
        this.stances = stances;
        this.plan = plan;
        this.runs = runs;
        this.hasLid = hasLid;
        this.bookings = bookings;
        this.changed = changed;
        this.spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(at), stances,
            new Workload.Custom())
            .needs(plan.totalNeeds(runs, hasLid, StockpotLoad.lid()))
            .gives(new Amount(plan.produces(), (long) plan.portions() * runs))
            .leaves(plan.workLeaves(runs, !hasLid))
            .effort(WorkEffort.NONE).vocation(WaresContent.trade())
            .gesture(WorkGesture.SWING).focus(at.cell())
            .estimate((int) Math.min(Integer.MAX_VALUE, (40L + Math.max(0, plan.recipe().time())) * runs))
            .doing(CookeryContent.COOKING, plan.produces()).done();
        nextBatch();
        closure = new KitchenClosure(how -> {}, changed);
    }

    private void nextBatch() {
        UUID batch = UUID.randomUUID();
        loading = new StockpotLoad(colony, batch, at, stances, plan, hasLid, bookings, changed, id(), generation);
        taking = new StockpotTake(colony, batch, at, stances, plan, bookings, changed,
            id(), served + 1 < runs, generation);
        loop = new ProcessLoop(this, false);
    }

    @Override
    public boolean producesBeforeSettlement() {
        return true;
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public List<Amount> planningLeaves(List<Amount> promised) {
        return Leaves.unpromised(spec.leaves(), promised);
    }

    @Override
    public boolean ready(ServerLevel level) {
        return bookings.cancelled(at, id(), generation) || bookings.first(at, id()) && loading.pot(level).isPresent();
    }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (closure.closed()) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        return loading.refusal(level);
    }

    @Override
    public WorkHolding acquire(HoldingContext context) {
        return new StockpotHolding();
    }

    private final class StockpotHolding extends ProcessHolding {

        StockpotHolding() {
            super((level, who) -> loop.next(level, who), () -> loop.over(),
                StockpotWork.this::commit, why -> loop.fail(why));
        }

        @Override
        protected HoldingStep stepCompleted(HoldingContext context, WorkAction operation, HoldingStep step) {
            StockpotWork.this.completed(operation, step instanceof HoldingStep.Failed failure
                ? new Ending.Failed(id(), failure.why()) : Ending.DONE);
            return new HoldingStep.Running();
        }
    }

    @Override
    public boolean turn(ServerLevel level) {
        return bookings.first(at, id());
    }

    @Override
    public boolean loadable(ServerLevel level) {
        return loading.ready(level);
    }

    @Override
    public WorkAction load(ServerLevel level, Worker who) {
        return loading;
    }

    @Override
    public boolean outputReady(ServerLevel level) {
        return taking.ready(level);
    }

    @Override
    public WorkAction take(ServerLevel level, Worker who) {
        return taking;
    }

    private void completed(WorkAction piece, Ending how) {
        if (how instanceof Ending.Failed failure) {
            loop.fail(failure.why());
        } else if (how instanceof Ending.Done) {
            if (piece == loading) {
                loop.loaded();
            } else if (piece == taking) {
                served++;
                if (served < runs) {
                    nextBatch();
                } else {
                    loop.finished();
                }
            }
        }
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return loop.isFinished() && !bookings.cancelled(at, id(), generation) ? Outcome.done()
            : Outcome.failed(loop.failure().orElse(CookeryRefusal.STATION_CHANGED));
    }

    @Override
    public KitchenClosure closure() {
        return closure;
    }

}
