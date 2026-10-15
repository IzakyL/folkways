package io.github.izakyl.folkways.plugins.cookery.stockpot;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IStockpot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.CookeryRefusal;
import io.github.izakyl.folkways.plugins.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.cookery.KitchenDelivery;
import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class StockpotLoad extends StockpotNode {

    private final UUID colony;
    private final UUID batch;
    private final UUID turn;
    private final long generation;
    private final StockpotPlan plan;
    private final boolean hasLid;
    private final KitchenBookings bookings;
    private final Runnable changed;

    StockpotLoad(UUID colony, UUID batch, WorldPos at, Stances stances, StockpotPlan plan,
                 boolean hasLid, KitchenBookings bookings, Runnable changed) {
        this(colony, batch, at, stances, plan, hasLid, bookings, changed, batch);
    }

    StockpotLoad(UUID colony, UUID batch, WorldPos at, Stances stances, StockpotPlan plan,
                 boolean hasLid, KitchenBookings bookings, Runnable changed, UUID turn) {
        this(colony, batch, at, stances, plan, hasLid, bookings, changed, turn, bookings.generation(at));
    }

    StockpotLoad(UUID colony, UUID batch, WorldPos at, Stances stances, StockpotPlan plan,
                 boolean hasLid, KitchenBookings bookings, Runnable changed, UUID turn, long generation) {
        super(spec(UUID.randomUUID(), at, stances)
            .needs(plan.loadingNeeds(hasLid, lid()).stream()
                .map(need -> new Need(need.spec(), need.count(), true)).toList())
            .leaves(plan.loadingLeaves())
            .doing(CookeryContent.PREPARING, plan.produces())
            .estimate(40 + Math.max(0, plan.recipe().time())).done(), at);
        this.colony = colony;
        this.batch = batch;
        this.turn = turn;
        this.generation = generation;
        this.plan = plan;
        this.hasLid = hasLid;
        this.bookings = bookings;
        this.changed = changed;
    }

    static ItemSpec lid() {
        return Goods.specOf(ModItems.STOCKPOT_LID.get().getDefaultInstance());
    }

    @Override
    public boolean ready(ServerLevel level) {
        if (bookings.cancelled(at, turn, generation)) {
            return true;
        }
        if (!bookings.first(at, turn)) {
            return false;
        }
        return pot(level).map(pot -> StockpotBatch.claimed(pot) ? false
            : pot.getStatus() != IStockpot.PUT_SOUP_BASE || pot.hasHeatSource(level)).orElse(false);
    }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (bookings.cancelled(at, turn, generation)) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        Optional<StockpotBlockEntity> found = pot(level);
        if (bookings.first(at, turn) && found.isPresent() && StockpotBatch.claimed(found.get())) {
            var owner = found.get().getPersistentData().getCompound(StockpotBatch.KEY);
            if (!StockpotBatch.ours(found.get(), colony) || !owner.hasUUID("batch")
                    || !batch.equals(owner.getUUID("batch"))) {
                return Optional.of(CookeryRefusal.STATION_CHANGED);
            }
        }
        return super.refusal(level);
    }
    @Override
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
        if (!bookings.first(at, turn) || StockpotBatch.claimed(pot)) {
            return Outcome.failed(CookeryRefusal.STATION_BUSY);
        }
        if (pot.getStatus() != IStockpot.PUT_SOUP_BASE || !pot.isEmpty() || pot.hasLid() != hasLid) {
            return Outcome.failed(CookeryRefusal.STATION_CHANGED);
        }
        if (!pot.hasHeatSource(level)) {
            return Outcome.failed(CookeryRefusal.NO_HEAT_SOURCE);
        }
        var current = StockpotPlan.find(level, plan.id()).orElse(null);
        if (current == null || StockpotPlan.of(level, current)
                .filter(now -> now.definition().equals(plan.definition())).isEmpty()) {
            return Outcome.failed(CookeryRefusal.RECIPE_CHANGED);
        }
        int ingredientCount = (int) plan.recipe().getIngredients().stream().filter(ingredient -> !ingredient.isEmpty()).count();
        var distinct = plan.flexible() ? java.util.stream.IntStream.rangeClosed(1, ingredientCount).boxed()
            .collect(java.util.stream.Collectors.toSet()) : java.util.Set.<Integer>of();
        Optional<StockpotSupplies> supplied = StockpotSupplies.draw(who.supplied(), plan.loadingSlots(hasLid, lid()), distinct);
        if (supplied.isEmpty()) {
            return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
        }
        StockpotBefore before = StockpotBefore.of(level, pot);
        List<ItemStack> made = new ArrayList<>();
        try {
            List<ItemStack> slots = supplied.get().slots();
            ItemStack lid;
            if (hasLid) {
                KitchenDelivery.Result opened = KitchenDelivery.run(level, pot.getBlockPos(), who, ItemStack.EMPTY,
                    () -> pot.onLitClick(level, who.body(), ItemStack.EMPTY));
                if (!opened.success() || opened.outputs().size() != 1
                        || !opened.outputs().getFirst().is(ModItems.STOCKPOT_LID.get())) {
                    return rolledBack(level, before);
                }
                lid = opened.outputs().getFirst();
            } else {
                lid = slots.get(1 + ingredientCount);
            }
            ItemStack soup = slots.getFirst();
            if (!act(level, pot, who, soup, () -> pot.addSoupBase(level, who.body(), soup), made)) {
                return rolledBack(level, before);
            }
            for (int index = 1; index <= ingredientCount; index++) {
                ItemStack ingredient = slots.get(index);
                if (!act(level, pot, who, ingredient,
                        () -> pot.addIngredient(level, who.body(), ingredient), made)) {
                    return rolledBack(level, before);
                }
            }
            if (!plan.selected(level, pot.getInput())) {
                return rolledBack(level, before);
            }
            ItemStack cover = lid;
            if (!act(level, pot, who, cover, () -> pot.onLitClick(level, who.body(), cover), made)) {
                return rolledBack(level, before);
            }
            StockpotBatch.start(colony, batch, plan, pot, level, hasLid).save(pot, level);
            // Carriers are preflighted before loading but spent only by serving. Within's one outer need books
            // them once; returning them as unused keeps their real components in the worker's pack meanwhile.
            List<ItemStack> unused = new ArrayList<>(supplied.get().unused());
            int firstCarrier = 1 + ingredientCount + (hasLid ? 0 : 1);
            for (int index = firstCarrier; index < slots.size(); index++) {
                unused.add(slots.get(index));
            }
            changed.run();
            return new Outcome.Done(List.of(), made, Optional.empty(), unused);
        } catch (RuntimeException | LinkageError broken) {
            before.restore(level);
            throw broken;
        }
    }

    private static boolean act(ServerLevel level, StockpotBlockEntity pot, Worker who, ItemStack hand,
                               Supplier<Boolean> action, List<ItemStack> made) {
        KitchenDelivery.Result result = KitchenDelivery.run(level, pot.getBlockPos(), who, hand, action);
        made.addAll(result.outputs());
        return result.success();
    }

    private static Outcome rolledBack(ServerLevel level, StockpotBefore before) {
        before.restore(level);
        return Outcome.failed(CookeryRefusal.STATION_CHANGED);
    }
}
