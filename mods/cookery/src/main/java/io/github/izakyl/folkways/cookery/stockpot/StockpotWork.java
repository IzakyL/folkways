package io.github.izakyl.folkways.cookery.stockpot;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.batch.BatchSession;
import io.github.izakyl.folkways.core.api.work.batch.BatchSpec;
import io.github.izakyl.folkways.core.api.work.batch.StepReceipt;
import io.github.izakyl.folkways.core.api.work.batch.StepResult;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.CookeryRefusal;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

// One production offer has one completion and one input budget. Its pieces use the real pot batch by batch;
// serving a first batch must not promise all later batches to the downstream consumer yet.
final class StockpotWork implements Node, BatchSession {

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
    private boolean loaded;
    private RefusalKind failed;

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
        this.spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(at), stances, this)
            .needs(plan.totalNeeds(runs, hasLid, StockpotLoad.lid()))
            .gives(new Amount(plan.produces(), (long) plan.portions() * runs))
            .leaves(plan.workLeaves(runs, !hasLid))
            .effort(WorkEffort.NONE).vocation(WaresContent.trade())
            .gesture(WorkGesture.SWING).focus(at.cell())
            .estimate((int) Math.min(Integer.MAX_VALUE, (40L + Math.max(0, plan.recipe().time())) * runs))
            .doing(CookeryContent.COOKING, plan.produces()).done();
        nextBatch();
    }

    private void nextBatch() {
        UUID batch = UUID.randomUUID();
        // Between batches the real lid stays on the empty pot. Only the first load draws a supplied lid;
        // the final serving restores an original appliance lid or returns the one this work brought.
        loading = new StockpotLoad(colony, batch, at, stances, plan, hasLid || served > 0, bookings, changed,
            id(), generation, hasLid);
        taking = new StockpotTake(colony, batch, at, stances, plan, bookings, changed, false, id(), served + 1 < runs, generation);
        loaded = false;
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
        return loading.refusal(level);
    }

    @Override
    public BatchSpec batch() {
        return BatchSpec.of(id());
    }

    @Override
    public StepResult advance(ServerLevel level, Worker who) {
        refusal(level).ifPresent(why -> failed = why);
        if (bookings.cancelled(at, id(), generation)) {
            failed = CookeryRefusal.STATION_CHANGED;
        }
        if (failed != null) return new StepResult.Failed(failed);
        if (served == runs) return new StepResult.Complete();
        if (!bookings.first(at, id())) {
            return new StepResult.Wait(10);
        }
        Node next = loaded ? taking : loading;
        return next.ready(level) ? new StepResult.Step(served + (loaded ? "/take" : "/load"), new Piece(next))
            : new StepResult.Wait(10);
    }

    @Override
    public void settled(ServerLevel level, StepReceipt receipt) {
        receipt.stop().ifPresent(why -> failed = why);
        if (receipt.step().equals(served + "/load")) {
            loaded = true;
        } else if (loaded && receipt.step().equals(served + "/take")) {
            served++;
            if (served < runs) nextBatch();
        }
    }

    @Override
    public void ended(ServerLevel level, Node piece, Ending how) {
        if (how instanceof Ending.Failed failure) {
            failed = failure.why();
        }
    }

    // An urge can abandon a wind-up without abandoning the outer work. A fresh piece id lets the same batch
    // resume afterwards; the persistent pot identity and its kitchen turn stay unchanged.
    private static final class Piece implements Node {
        private final Node delegate;
        private final NodeSpec spec;

        Piece(Node delegate) {
            this.delegate = delegate;
            NodeSpec source = delegate.spec();
            this.spec = new NodeSpec(UUID.randomUUID(), source.owner(), source.site(), source.stances(),
                source.needs(), source.gives(), source.leaves(), source.tools(), source.workload(), source.effort(),
                source.pace(), source.vocation(), source.gesture(), source.focus(), source.estimate(),
                source.doing(), source.about(), source.subject());
        }

        public NodeSpec spec() { return spec; }
        public boolean ready(ServerLevel level) { return delegate.ready(level); }
        public Optional<RefusalKind> refusal(ServerLevel level) { return delegate.refusal(level); }
        public Outcome commit(ServerLevel level, Worker who) { return delegate.commit(level, who); }
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return served == runs && !bookings.cancelled(at, id(), generation) ? Outcome.done()
            : Outcome.failed(failed == null ? CookeryRefusal.STATION_CHANGED : failed);
    }

    @Override
    public Outcome finish(ServerLevel level, Worker who) {
        return commit(level, who);
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        changed.run();
    }
}
