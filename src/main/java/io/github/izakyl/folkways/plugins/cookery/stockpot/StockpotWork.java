package io.github.izakyl.folkways.plugins.cookery.stockpot;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.CookeryRefusal;
import io.github.izakyl.folkways.plugins.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

// One production offer has one completion and one input budget. Its pieces use the real pot batch by batch;
// serving a first batch must not promise all later batches to the downstream consumer yet.
final class StockpotWork implements Node, Workload.Within {

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
        loading = new StockpotLoad(colony, batch, at, stances, plan, hasLid, bookings, changed, id(), generation);
        taking = new StockpotTake(colony, batch, at, stances, plan, bookings, changed, false, id(), served + 1 < runs, generation);
        loaded = false;
    }

    @Override
    public NodeSpec spec() {
        return spec;
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
    public Turn next(ServerLevel level, Worker who) {
        refusal(level).ifPresent(why -> failed = why);
        if (bookings.cancelled(at, id(), generation)) {
            failed = CookeryRefusal.STATION_CHANGED;
        }
        if (failed != null || served == runs) {
            return LEAVE;
        }
        if (!bookings.first(at, id())) {
            return WAIT;
        }
        Node next = loaded ? taking : loading;
        return next.ready(level) ? new Turn.Piece(new Piece(next)) : WAIT;
    }

    @Override
    public void ended(ServerLevel level, Node piece, Ending how) {
        if (how instanceof Ending.Failed failure) {
            failed = failure.why();
        } else if (how instanceof Ending.Done && piece instanceof Piece action) {
            if (action.delegate == loading) {
                loaded = true;
            } else if (action.delegate == taking) {
                served++;
                if (served < runs) {
                    nextBatch();
                }
            }
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
    public void ended(ServerLevel level, Ending how) {
        changed.run();
    }
}
