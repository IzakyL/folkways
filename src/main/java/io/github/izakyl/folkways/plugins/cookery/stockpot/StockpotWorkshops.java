package io.github.izakyl.folkways.plugins.cookery.stockpot;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IStockpot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public final class StockpotWorkshops {

    // Only successfully planned autonomous recovery roots are kept here, never ordinary recipe descendants.
    private record RecoveryKey(UUID colony, WorldPos at) { }
    private static final java.util.Map<RecoveryKey, Grown> RECOVERING = new java.util.concurrent.ConcurrentHashMap<>();
    static void rememberRecovery(UUID colony, WorldPos at, StockpotTake work) { RECOVERING.put(new RecoveryKey(colony, at), work.recoveryGoal()); }
    static void endRecovery(UUID colony, WorldPos at, StockpotTake work) { RECOVERING.remove(new RecoveryKey(colony, at), work.recoveryGoal()); }
    public static List<Grown> activeRecovery(UUID colony, ServerLevel level, WorldPos at, KitchenBookings bookings) {
        if (!at.in(level)) { return List.of(); }
        RecoveryKey key = new RecoveryKey(colony, at);
        Grown active = RECOVERING.get(key);
        if (active == null) { return List.of(); }
        if (bookings.first(at, active.nodes().getFirst().id())) { return List.of(active); }
        RECOVERING.remove(key, active);
        return List.of();
    }
    public static void stop(UUID colony) { RECOVERING.keySet().removeIf(key -> key.colony().equals(colony)); }

    private StockpotWorkshops() {
    }

    public static Optional<Workshop> freeze(UUID key, UUID colonyId, ServerLevel level, WorldPos at,
                                            Stances stances, KitchenBookings bookings, Runnable changed) {
        Optional<StockpotBlockEntity> found = pot(level, at);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        StockpotBlockEntity pot = found.get();
        if (StockpotBatch.claimed(pot) && pot.getStatus() == IStockpot.PUT_SOUP_BASE && pot.isEmpty()) {
            var marker = pot.getPersistentData().getCompound(StockpotBatch.KEY);
            boolean stillHeld = marker.hasUUID("colony") && Colonies.of(level.getServer(), marker.getUUID("colony"))
                .flatMap(colony -> colony.holdingAt(at))
                .filter(holding -> holding.owner().equals(CookeryContent.STATION)).isPresent();
            if (!stillHeld) {
                // An unloaded unmark may leave only a marker. Empty cookware can be reclaimed; invested goods cannot.
                StockpotBatch.clear(pot);
                changed.run();
            }
        }
        List<StockpotPlan> recipes = new ArrayList<>();
        if (!StockpotBatch.claimed(pot) && pot.getStatus() == IStockpot.PUT_SOUP_BASE && pot.isEmpty()) {
            for (var holder : level.getRecipeManager().getAllRecipesFor(ModRecipes.STOCKPOT_RECIPE)) {
                StockpotPlan.of(level, holder).ifPresent(recipes::add);
            }
            for (var holder : level.getRecipeManager().getAllRecipesFor(ModRecipes.FLEX_STOCKPOT_RECIPE)) {
                StockpotPlan.of(level, holder).ifPresent(recipes::add);
            }
        }
        return Optional.of(new Frozen(key, colonyId, at, stances, List.copyOf(recipes), pot.hasLid(), bookings, changed));
    }

    public static List<Grown> recovery(UUID colonyId, ServerLevel level, WorldPos at, Stances stances,
                                       KitchenBookings bookings, Runnable changed) {
        var active = activeRecovery(colonyId, level, at, bookings);
        if (!active.isEmpty()) { return active; }
        Optional<StockpotBlockEntity> found = pot(level, at);
        if (found.isEmpty() || !StockpotBatch.ours(found.get(), colonyId)) {
            return List.of();
        }
        StockpotBlockEntity pot = found.get();
        Optional<StockpotBatch> batch = StockpotBatch.load(pot, level, colonyId)
            .filter(current -> current.valid(pot, level));
        if (batch.isEmpty()) {
            // Player changes are theirs to finish or empty. Relinquish the claim without changing their contents.
            StockpotBatch.clear(pot);
            changed.run();
            return List.of();
        }
        if (!bookings.free(at)) {
            return List.of();
        }
        StockpotBatch current = batch.get();
        return List.of(new StockpotTake(colonyId, current.id(), at, stances, current.plan(), bookings,
            changed, true).recoveryGoal());
    }

    public static void release(UUID colonyId, ServerLevel level, WorldPos at) {
        RECOVERING.remove(new RecoveryKey(colonyId, at));
        pot(level, at).filter(current -> StockpotBatch.ours(current, colonyId)).ifPresent(StockpotBatch::clear);
    }
    private static Optional<StockpotBlockEntity> pot(ServerLevel level, WorldPos at) {
        return at.in(level) && level.isLoaded(at.block(level))
            && level.getBlockEntity(at.block(level)) instanceof StockpotBlockEntity pot
            ? Optional.of(pot) : Optional.empty();
    }

    // Only values copied on the server thread reach options, which the planner calls on its own thread.
    private record Frozen(UUID key, UUID colony, WorldPos at, Stances stances, List<StockpotPlan> recipes,
                          boolean hasLid, KitchenBookings bookings, Runnable changed) implements Workshop {

        @Override
        public ResourceLocation id() {
            return CookeryContent.ID;
        }

        @Override
        public WorkSite site() {
            return new WorkSite.AtBlock(at);
        }

        @Override
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce produce)) {
                return List.of();
            }
            List<Refinement.Change> options = new ArrayList<>();
            for (StockpotPlan plan : recipes) {
                if (!Goods.overlap(produce.wanted(), plan.produces())) {
                    continue;
                }
                long runs = produce.runs(site(), plan.perRunNeeds(), plan.portions(),
                    Optional.of(WaresContent.trade()), Integer.MAX_VALUE);
                StockpotWork work = new StockpotWork(colony, at, stances, plan, (int) runs, hasLid, bookings, changed);
                options.add(Refinement.Change.of(Grown.of(work)).holding(bookings.hold(at, work.id())));
            }
            return List.copyOf(options);
        }
    }
}
