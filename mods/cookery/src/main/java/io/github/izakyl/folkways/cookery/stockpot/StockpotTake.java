package io.github.izakyl.folkways.cookery.stockpot;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IStockpot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.CookeryRefusal;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.cookery.KitchenDelivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Xp;
import io.github.izakyl.folkways.core.work.support.Leaves;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

class StockpotTake extends StockpotAction {

    final UUID colony;
    final UUID turn;
    final long generation;
    private final boolean keepLid;
    private final StockpotPlan plan;
    final KitchenBookings bookings;
    private final Runnable changed;

    StockpotTake(UUID colony, UUID batch, WorldPos at, Stances stances, StockpotPlan plan,
        KitchenBookings bookings, Runnable changed) {
        this(colony, batch, at, stances, plan, bookings, changed, batch, false);
    }

    StockpotTake(UUID colony, UUID batch, WorldPos at, Stances stances, StockpotPlan plan,
        KitchenBookings bookings, Runnable changed,
        UUID turn, boolean keepLid) {
        this(colony, batch, at, stances, plan, bookings, changed, turn, keepLid, bookings.generation(at));
    }

    StockpotTake(UUID colony, UUID batch, WorldPos at, Stances stances, StockpotPlan plan,
        KitchenBookings bookings, Runnable changed, UUID turn, boolean keepLid,
                 long generation) {
        super(spec(batch, at, stances)
            .needs(plan.carrier().map(need -> List.of(
                new Need(need.spec(), need.count(), true)))
                .orElseGet(List::of))
            .gives(new Amount(plan.produces(), plan.portions()))
            .leaves(plan.servingLeaves(plan.portions(), !keepLid))
            .doing(CookeryContent.TAKING, plan.produces()).done(), at);
        this.colony = colony;
        this.turn = turn;
        this.generation = generation;
        this.keepLid = keepLid;
        this.plan = plan;
        this.bookings = bookings;
        this.changed = changed;
    }

    public List<Amount> planningLeaves(List<Amount> promised) {
        return Leaves.unpromised(spec().leaves(), promised);
    }

    public boolean ready(ServerLevel level) {
        if (bookings.cancelled(at, turn, generation)) {
            return true;
        }
        if (!bookings.first(at, turn)) {
            return false;
        }
        return pot(level).map(pot -> {
            if (!StockpotBatch.claimed(pot)) {
                // The load dependency gates execution. A lost claim must reach commit and fail, not hold
                // its kitchen booking forever after a player change or a recovery sweep.
                return true;
            }
            Optional<StockpotBatch> batch = batch(pot, level);
            return batch.isEmpty() || !batch.get().valid(pot, level) || pot.getStatus() == IStockpot.FINISHED;
        }).orElse(false);
    }

    public Optional<RefusalKind> refusal(ServerLevel level) {
        return bookings.cancelled(at, turn, generation) ? Optional.of(CookeryRefusal.STATION_CHANGED) : super.refusal(level);
    }

    public Outcome commit(ServerLevel level, Worker who) {
        if (bookings.cancelled(at, turn, generation)) {
            return Outcome.failed(CookeryRefusal.STATION_CHANGED);
        }
        Optional<StockpotBlockEntity> found = pot(level);
        if (found.isEmpty()) {
            return Outcome.failed(CookeryRefusal.NOT_A_STATION);
        }
        if (!canWork(level, who)) {
            return Outcome.failed(CookeryRefusal.STATION_CHANGED);
        }
        StockpotBlockEntity pot = found.get();
        Optional<StockpotBatch> batch = batch(pot, level);
        if (!bookings.first(at, turn)) {
            return Outcome.failed(CookeryRefusal.STATION_BUSY);
        }
        if (batch.isEmpty() || !batch.get().valid(pot, level)) {
            return Outcome.failed(CookeryRefusal.STATION_CHANGED);
        }
        if (pot.getStatus() != IStockpot.FINISHED) {
            return Outcome.failed(CookeryRefusal.STATION_BUSY);
        }
        List<Predicate<ItemStack>> carriers = new ArrayList<>();
        for (int serving = 0; serving < (plan.carrier().isPresent() ? plan.portions() : 0); serving++) {
            carriers.add(plan.recipe().carrier());
        }
        Optional<StockpotSupplies> supplied = StockpotSupplies.draw(who.supplied(), carriers);
        if (supplied.isEmpty()) {
            return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
        }
        StockpotBefore before = StockpotBefore.of(level, pot);
        List<ItemStack> made = new ArrayList<>();
        try {
            KitchenDelivery.Result opened = KitchenDelivery.run(level, pot.getBlockPos(), who, ItemStack.EMPTY,
                () -> pot.onLitClick(level, who.body(), ItemStack.EMPTY));
            if (!opened.success() || opened.outputs().size() != 1
                    || !opened.outputs().getFirst().is(ModItems.STOCKPOT_LID.get())) {
                return rolledBack(level, before);
            }
            ItemStack lid = opened.outputs().getFirst();
            List<ItemStack> unused = new ArrayList<>(supplied.get().unused());
            for (int serving = 0; serving < plan.portions(); serving++) {
                ItemStack carrier = plan.carrier().isPresent() ? supplied.get().slots().get(serving) : ItemStack.EMPTY;
                int remaining = pot.getTakeoutCount();
                KitchenDelivery.Result taken = KitchenDelivery.run(level, pot.getBlockPos(), who, carrier,
                    () -> pot.takeOutProduct(level, who.body(), carrier));
                long dishes = taken.outputs().stream()
                    .filter(stack -> ItemStack.isSameItemSameComponents(stack, batch.get().result(level)))
                    .mapToInt(ItemStack::getCount).sum();
                if (!taken.success() || pot.getTakeoutCount() != remaining - 1 || dishes != 1) {
                    return rolledBack(level, before);
                }
                made.addAll(taken.outputs());
            }
            if (pot.getStatus() != IStockpot.PUT_SOUP_BASE) {
                return rolledBack(level, before);
            }
            if (batch.get().replaceLid()) {
                KitchenDelivery.Result closed = KitchenDelivery.run(level, pot.getBlockPos(), who, lid,
                    () -> pot.onLitClick(level, who.body(), lid));
                if (!closed.success()) {
                    return rolledBack(level, before);
                }
                made.addAll(closed.outputs());
            } else if (keepLid) {
                unused.add(lid);
            } else {
                made.add(lid);
            }
            StockpotBatch.clear(pot);
            changed.run();
            return new Outcome.Done(List.of(), made, Optional.of(new Xp(WaresContent.trade(), plan.portions())),
                unused);
        } catch (RuntimeException | LinkageError broken) {
            before.restore(level);
            throw broken;
        }
    }

    private Optional<StockpotBatch> batch(StockpotBlockEntity pot, ServerLevel level) {
        return StockpotBatch.load(pot, level, colony)
            .filter(batch -> batch.id().equals(id()) && batch.plan().definition().equals(plan.definition()));
    }

    private static Outcome rolledBack(ServerLevel level, StockpotBefore before) {
        before.restore(level);
        return Outcome.failed(CookeryRefusal.STATION_CHANGED);
    }

    static final class Recovery extends StockpotTake implements Node {
        private final Grown goal;
        private Hold held;
        private boolean ended;

        Recovery(
            UUID colony,
            UUID batch,
            WorldPos at,
            Stances stances,
            StockpotPlan plan,
            KitchenBookings bookings,
            Runnable changed) {
            super(colony, batch, at, stances, plan, bookings, changed);
            goal = Grown.of(this);
        }

        Grown recoveryGoal() {
            return goal;
        }

        @Override
        public Optional<RefusalKind> planned(ServerLevel level) {
            if (held != null) {
                return Optional.empty();
            }
            if (bookings.generation(at) != generation) {
                return Optional.of(CookeryRefusal.STATION_CHANGED);
            }
            if (!bookings.free(at)) {
                return Optional.of(CookeryRefusal.STATION_BUSY);
            }
            Hold claim = bookings.hold(at, turn, spec().estimate());
            Optional<RefusalKind> refused = claim.take();
            if (refused.isEmpty()) {
                held = claim;
                StockpotWorkshops.rememberRecovery(colony, at, this);
            }
            return refused;
        }

        @Override
        public Optional<RefusalKind> refusal(ServerLevel level) {
            return ended ? Optional.of(CookeryRefusal.STATION_CHANGED) : super.refusal(level);
        }

        @Override
        public void released(ServerLevel level, Worker who, Ending how) {
            if (!(how instanceof Ending.Done)) {
                ended(level, how);
            }
        }

        @Override
        public void ended(ServerLevel level, Ending how) {
            if (ended) {
                return;
            }
            ended = true;
            StockpotWorkshops.endRecovery(colony, at, this);
            if (held != null) {
                held.release(how);
                held = null;
            }
        }
    }
}
