package io.github.izakyl.folkways.cookery.pot;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IPot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.CookeryRefusal;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.cookery.KitchenDelivery;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

final class PotWork implements Node, Workload.Within {
    static final ToolNeed SHOVEL = new ToolNeed.Tagged(TagMod.KITCHEN_SHOVEL);
    private final NodeSpec spec;
    private final UUID colony;
    private final WorldPos at;
    private final PotPlan plan;
    private final int runs;
    private final KitchenBookings bookings;
    private final long generation;
    private final Runnable changed;
    private final boolean recovering;
    private final Grown recoveryGoal;
    private UUID batch;
    private boolean loaded;
    private int served;
    private RefusalKind failed;
    private Hold recoveryHold;

    PotWork(UUID colony, WorldPos at, Stances stances, PotPlan plan, int runs, KitchenBookings bookings, Runnable changed) {
        this(colony, at, stances, plan, runs, bookings, changed, null, null, null);
    }

    static PotWork recover(WorldPos at, Stances stances, PotBatch batch, PotBlockEntity pot,
                           ServerLevel level, KitchenBookings bookings, Runnable changed) {
        return new PotWork(batch.colony(), at, stances, batch.plan(), 1, bookings, changed, batch, pot, level);
    }

    private PotWork(UUID colony, WorldPos at, Stances stances, PotPlan plan, int runs, KitchenBookings bookings,
                    Runnable changed, PotBatch previous, PotBlockEntity pot, ServerLevel level) {
        this.colony = colony;
        this.at = at;
        this.plan = plan;
        this.runs = runs;
        this.bookings = bookings;
        this.generation = bookings.generation(at);
        this.changed = changed;
        recovering = previous != null;
        loaded = recovering;
        batch = recovering ? previous.id() : UUID.randomUUID();
        UUID id = recovering ? UUID.nameUUIDFromBytes(("folkways:pot/recover/" + batch).getBytes(StandardCharsets.UTF_8)) : UUID.randomUUID();
        List<Need> needs = recovering ? previous.servingNeeds(pot, level) : plan.total(runs);
        List<Amount> gives = recovering && pot.getStatus() >= IPot.FINISHED && plan.mode() != PotPlan.Mode.DARK
            ? Leaves.ofStacks(List.of(previous.product(pot))) : List.of(new Amount(plan.produces(), (long) plan.count() * runs));
        boolean shovel = !recovering || pot.getStatus() < IPot.FINISHED || previous.carrier(pot, level).isEmpty();
        spec = declare(id, stances, this).needs(needs).gives(gives).leaves(plan.leaves(runs))
            .tools(shovel ? List.of(new ToolUse(SHOVEL, 0)) : List.of()).effort(WorkEffort.NONE)
            .estimate((int) Math.min(Integer.MAX_VALUE, ((long) plan.recipe().time() + (plan.mode() == PotPlan.Mode.DARK ? 800L : 0L) + 20L * (plan.recipe().stirFryCount() + 2)) * runs))
            .doing(CookeryContent.COOKING, plan.produces()).done();
        recoveryGoal = recovering ? Grown.of(this) : null;
    }

    private NodeSpec.Builder declare(UUID id, Stances stances, Workload workload) {
        return NodeSpec.of(id, CookeryContent.ID, new WorkSite.AtBlock(at), stances, workload)
            .vocation(WaresContent.trade()).gesture(WorkGesture.SWING).focus(at.cell());
    }

    public NodeSpec spec() { return spec; }

    @Override
    public List<Amount> planningLeaves(List<Amount> promised) {
        return Leaves.unpromised(spec.leaves(), promised);
    }
    Grown recoveryGoal() { return recoveryGoal; }

    public Optional<RefusalKind> planned(ServerLevel level) {
        if (!recovering) return bookings.cancelled(at, id(), generation) ? Optional.of(CookeryRefusal.STATION_CHANGED) : Optional.empty();
        if (recoveryHold != null) return bookings.valid(at, id(), generation) ? Optional.empty() : Optional.of(CookeryRefusal.STATION_CHANGED);
        if (generation != bookings.generation(at) || !bookings.free(at) || current(level).isEmpty()) {
            return Optional.of(CookeryRefusal.STATION_BUSY);
        }
        recoveryHold = bookings.hold(at, id(), spec().estimate());
        var refused = recoveryHold.take();
        if (refused.isEmpty()) PotStation.rememberRecovery(colony, at, this);
        return refused;
    }

    public boolean ready(ServerLevel level) {
        return bookings.valid(at, id(), generation) && bookings.first(at, id()) && PotStation.pot(level, at).isPresent();
    }

    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (bookings.cancelled(at, id(), generation)) return Optional.of(CookeryRefusal.STATION_CHANGED);
        if (!at.in(level) || !level.isLoaded(at.block(level))) return Optional.empty();
        Optional<PotBlockEntity> found = PotStation.pot(level, at);
        if (found.isEmpty()) return Optional.of(CookeryRefusal.NOT_A_STATION);
        if (served == runs) return Optional.empty();
        if (loaded) return current(level).isPresent() ? Optional.empty() : Optional.of(CookeryRefusal.STATION_CHANGED);
        return bookings.first(at, id()) && (!PotStation.empty(found.get()) || PotBatch.claimed(found.get()))
            ? Optional.of(CookeryRefusal.STATION_CHANGED) : Optional.empty();
    }

    private Optional<PotBatch> current(ServerLevel level) {
        return PotStation.pot(level, at).flatMap(pot -> PotBatch.load(pot, level, colony)
            .filter(owner -> owner.id().equals(batch) && owner.valid(pot, level)));
    }

    public Turn next(ServerLevel level, Worker who) {
        refusal(level).ifPresent(why -> failed = why);
        if (failed != null || served == runs) return LEAVE;
        if (!bookings.first(at, id())) return WAIT;
        if (!loaded) return new Turn.Piece(new Load());
        PotBlockEntity pot = PotStation.pot(level, at).orElseThrow();
        PotBatch owner = current(level).orElseThrow();
        if (pot.getStatus() == IPot.PUT_INGREDIENT || pot.getStatus() == IPot.COOKING && PotBatch.stirs(pot, level) > 0) {
            return new Turn.Piece(new Stir());
        }
        if (plan.mode() == PotPlan.Mode.DARK && pot.getStatus() == IPot.FINISHED) return WAIT;
        return pot.getStatus() >= IPot.FINISHED ? new Turn.Piece(new Take(owner, pot, level)) : WAIT;
    }

    public void ended(ServerLevel level, Node piece, Ending how) {
        if (how instanceof Ending.Failed failure) {
            failed = failure.why();
        } else if (how == Ending.DONE) {
            if (piece instanceof Load) loaded = true;
            if (piece instanceof Take take) {
                served++;
                if (!take.targetMet && !recovering) failed = CookeryRefusal.STATION_CHANGED;
                if (served < runs && failed == null) {
                    loaded = false;
                    batch = UUID.randomUUID();
                }
            }
        }
    }

    public Outcome commit(ServerLevel level, Worker who) {
        return served == runs && failed == null && bookings.valid(at, id(), generation) ? Outcome.done()
            : Outcome.failed(failed == null ? CookeryRefusal.STATION_CHANGED : failed);
    }

    public void ended(ServerLevel level, Ending how) {
        if (recoveryHold != null) recoveryHold.release(how);
        if (recovering) PotStation.endRecovery(colony, at, this);
        nudge();
    }

    private void nudge() {
        try { changed.run(); } catch (RuntimeException | LinkageError broken) {
            org.slf4j.LoggerFactory.getLogger("folkways-cookery").warn("pot refresh failed after settlement", broken);
        }
    }

    private boolean reaches(ServerLevel level, Worker who) {
        return at.in(level) && level.isLoaded(at.block(level)) && Reach.standingIn(who.body(), spec.stances())
            && PotStation.operatingFeet(level, at, who.body())
                .filter(feet -> Reach.withinReach(feet, at.block(level)) && Reach.inSight(level, feet, at.block(level))).isPresent();
    }

    private abstract class Action implements Node {
        final NodeSpec action;
        Action(List<Need> needs, boolean shovel, net.minecraft.resources.ResourceLocation doing) {
            action = declare(UUID.randomUUID(), spec.stances(), Workload.Once.of(20, WaresContent::haste))
                .needs(needs).tools(shovel ? List.of(new ToolUse(SHOVEL, 0)) : List.of())
                .leaves(plan.leaves(1)).doing(doing, plan.produces()).done();
        }
        public NodeSpec spec() { return action; }
        public Optional<RefusalKind> refusal(ServerLevel level) {
            return bookings.cancelled(at, PotWork.this.id(), generation) ? Optional.of(CookeryRefusal.STATION_CHANGED) : Optional.empty();
        }
        boolean allowed(ServerLevel level, Worker who) {
            return bookings.valid(at, PotWork.this.id(), generation) && bookings.first(at, PotWork.this.id()) && reaches(level, who);
        }
    }

    private final class Load extends Action {
        Load() { super(plan.total(1), false, CookeryContent.PREPARING); }
        public Outcome commit(ServerLevel level, Worker who) {
            Optional<PotBlockEntity> found = PotStation.pot(level, at);
            if (!allowed(level, who) || found.isEmpty() || !PotStation.empty(found.get()) || PotBatch.claimed(found.get())
                    || who.held(SHOVEL).isEmpty()) return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            PotBlockEntity pot = found.get();
            if (!pot.hasHeatSource(level)) return Outcome.failed(CookeryRefusal.NO_HEAT_SOURCE);
            if (PotPlan.find(level, plan.id()).filter(now -> now.definition().equals(plan.definition()) && now.flexible() == plan.flexible()).isEmpty()) {
                return Outcome.failed(CookeryRefusal.RECIPE_CHANGED);
            }
            Set<Integer> distinct = plan.flexible() ? IntStream.rangeClosed(1, plan.ingredientCount()).boxed().collect(Collectors.toSet()) : Set.of();
            Optional<PotSupplies> supplied = PotSupplies.draw(who.supplied(), plan.slots(), distinct);
            if (supplied.isEmpty()) return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
            List<ItemStack> slots = supplied.get().slots();
            int carrierStart = 1 + plan.ingredientCount();
            if (plan.carrier().isPresent() && !sameCarrier(slots.subList(carrierStart, slots.size()))) {
                return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
            }
            PotBefore before = PotBefore.of(level, pot);
            List<ItemStack> made = new ArrayList<>();
            try {
                ItemStack oil = slots.getFirst();
                var oiled = KitchenDelivery.run(level, pot.getBlockPos(), who, oil, () -> pot.onPlaceOil(level, who.body(), oil));
                made.addAll(oiled.outputs());
                if (!oiled.success()) return rollback(level, before);
                for (int index = 1; index <= plan.ingredientCount(); index++) {
                    ItemStack input = slots.get(index);
                    var added = KitchenDelivery.run(level, pot.getBlockPos(), who, input, () -> pot.addIngredient(level, who.body(), input));
                    made.addAll(added.outputs());
                    if (!added.success()) return rollback(level, before);
                }
                if (!plan.selected(level, pot.getContainer())) return rollback(level, before);
                PotBatch.start(colony, batch, plan, pot, level).save(pot, level);
                List<ItemStack> unused = new ArrayList<>(supplied.get().unused());
                unused.addAll(slots.subList(carrierStart, slots.size()));
                nudge();
                return new Outcome.Done(List.of(), made, Optional.empty(), unused);
            } catch (RuntimeException | LinkageError broken) {
                before.restore(level);
                throw broken;
            }
        }
    }

    private final class Stir extends Action {
        Stir() { super(List.of(), true, CookeryContent.COOKING); }
        public Outcome commit(ServerLevel level, Worker who) {
            Optional<PotBlockEntity> found = PotStation.pot(level, at);
            if (!allowed(level, who) || found.isEmpty() || current(level).isEmpty()) return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            PotBlockEntity pot = found.get();
            if (!pot.hasHeatSource(level)) return Outcome.failed(CookeryRefusal.NO_HEAT_SOURCE);
            ItemStack shovel = who.held(SHOVEL);
            if (shovel.isEmpty()) return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
            if (pot.getStatus() != IPot.PUT_INGREDIENT && pot.getStatus() != IPot.COOKING) return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            if (pot.getStatus() == IPot.PUT_INGREDIENT && !plan.selected(level, pot.getContainer()))
                return Outcome.failed(CookeryRefusal.RECIPE_CHANGED);
            PotBefore before = PotBefore.of(level, pot);
            KitchenDelivery.Result interaction;
            try {
                interaction = KitchenDelivery.run(level, pot.getBlockPos(), who, shovel, () -> {
                    pot.onShovelHit(level, who.body(), shovel);
                    return true;
                });
                if (pot.getStatus() != IPot.COOKING || current(level).isEmpty()) return rollback(level, before);
            } catch (RuntimeException | LinkageError broken) {
                before.restore(level);
                throw broken;
            }
            // Match PotBlock's successful shovel interaction; take-out itself does not incur this wear.
            if (level.random.nextDouble() < 0.25D) {
                shovel.hurtAndBreak(1, level, who.body(), ignored -> { });
                who.pack().setChanged();
            }
            nudge();
            return new Outcome.Done(List.of(), interaction.outputs(), Optional.empty());
        }
    }

    private final class Take extends Action {
        private final PotBatch owner;
        private final Ingredient carrier;
        private final ItemStack product;
        boolean targetMet;
        Take(PotBatch owner, PotBlockEntity pot, ServerLevel level) {
            super(owner.servingNeeds(pot, level), owner.carrier(pot, level).isEmpty(), CookeryContent.TAKING);
            this.owner = owner;
            carrier = owner.carrier(pot, level);
            product = owner.product(pot);
            targetMet = plan.mode() == PotPlan.Mode.DARK
                ? pot.getStatus() == IPot.BURNT && Goods.matches(product, plan.produces())
                : pot.getStatus() == IPot.FINISHED && ItemStack.matches(product, owner.result());
        }
        public Outcome commit(ServerLevel level, Worker who) {
            Optional<PotBlockEntity> found = PotStation.pot(level, at);
            if (!allowed(level, who) || found.isEmpty() || current(level).isEmpty()) return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            PotBlockEntity pot = found.get();
            if (pot.getStatus() < IPot.FINISHED || !ItemStack.matches(owner.product(pot), product)) return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            List<Predicate<ItemStack>> checks = new ArrayList<>();
            for (int index = 0; index < (carrier.isEmpty() ? 0 : product.getCount()); index++) checks.add(carrier);
            Optional<PotSupplies> supplied = PotSupplies.draw(who.supplied(), checks);
            if (supplied.isEmpty() || !sameCarrier(supplied.get().slots())) return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
            ItemStack hand;
            if (carrier.isEmpty()) {
                hand = who.held(SHOVEL);
                if (hand.isEmpty()) return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
            } else hand = supplied.get().slots().getFirst().copyWithCount(product.getCount());
            PotBefore before = PotBefore.of(level, pot);
            try {
                var taken = KitchenDelivery.run(level, pot.getBlockPos(), who, hand, () -> pot.takeOutProduct(level, who.body(), hand));
                long count = taken.outputs().stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, product)).mapToLong(ItemStack::getCount).sum();
                if (!taken.success() || !PotStation.empty(pot) || count != product.getCount()) return rollback(level, before);
                PotBatch.clear(pot);
                nudge();
                return new Outcome.Done(List.of(), taken.outputs(), targetMet ? Optional.of(new Xp(WaresContent.trade(), plan.count())) : Optional.empty(), supplied.get().unused());
            } catch (RuntimeException | LinkageError broken) {
                before.restore(level);
                throw broken;
            }
        }
    }

    private static boolean sameCarrier(List<ItemStack> slots) {
        return slots.isEmpty() || slots.size() <= slots.getFirst().getMaxStackSize()
            && slots.stream().allMatch(stack -> ItemStack.isSameItemSameComponents(stack, slots.getFirst()));
    }

    private static Outcome rollback(ServerLevel level, PotBefore before) {
        before.restore(level);
        return Outcome.failed(CookeryRefusal.STATION_CHANGED);
    }
}
