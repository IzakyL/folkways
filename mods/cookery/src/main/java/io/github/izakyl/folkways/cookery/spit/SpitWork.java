package io.github.izakyl.folkways.cookery.spit;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.ShawarmaSpitBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.CookeryRefusal;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.cookery.KitchenClosure;
import io.github.izakyl.folkways.cookery.KitchenDelivery;
import io.github.izakyl.folkways.cookery.KitchenHolding;
import io.github.izakyl.folkways.cookery.KitchenStation;
import io.github.izakyl.folkways.cookery.KitchenWork;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemDrops;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.ToolUse;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.support.Leaves;
import io.github.izakyl.folkways.core.work.support.WorkStances;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;

final class SpitWork implements KitchenWork {
    static final ToolNeed KNIFE = new ToolNeed.Tagged(TagMod.KITCHEN_KNIFE);
    private final UUID colony;
    private final UUID batch;
    private final WorldPos at;
    private final WorldPos part;
    private final Stances stances;
    private final SpitStation.Job job;
    private final int count;
    private final KitchenBookings bookings;
    private final long generation;
    private final Runnable changed;
    private final boolean recovering;
    private final NodeSpec spec;
    private final Grown recoveryGoal;
    private Hold hold;
    private boolean loaded;
    private boolean finished;
    private RefusalKind failed;
    private final KitchenClosure closure;

    SpitWork(UUID colony, WorldPos at, WorldPos part, Stances stances, SpitStation.Job job, int count,
             KitchenBookings bookings, Runnable changed, SpitBatch.Batch previous) {
        this.colony = colony; this.at = at; this.part = part; this.stances = stances; this.job = job; this.count = count;
        this.bookings = bookings; this.generation = bookings.generation(at); this.changed = changed;
        this.recovering = previous != null; this.loaded = recovering;
        batch = recovering ? previous.id() : UUID.randomUUID();
        spec = NodeSpec.of(batch, CookeryContent.ID, new WorkSite.AtBlock(at), stances, new Workload.Custom())
            .needs(loaded ? List.of() : List.of(new Need(job.input(), count, true)))
            .gives(new Amount(Goods.specOf(job.output()), count)).leaves(List.of(new Amount(Goods.specOf(job.output()), count)))
            .tools(new ToolUse(KNIFE, 0)).effort(WorkEffort.NONE).vocation(WaresContent.trade())
            .estimate(40 + Math.max(0, job.time())).doing(CookeryContent.COOKING).done();
        recoveryGoal = recovering ? Grown.of(this) : null;
        closure = new KitchenClosure(how -> {
            if (recovering) {
                SpitStation.ended(colony, at, this);
            }
            if (hold != null) {
                hold.release(how);
                hold = null;
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
        if (generation != bookings.generation(at) || !bookings.free(at)) { return Optional.of(CookeryRefusal.STATION_CHANGED); }
        hold = bookings.hold(at, id(), spec().estimate());
        Optional<RefusalKind> refused = hold.take();
        if (refused.isEmpty()) { SpitStation.keep(colony, at, recoveryGoal, generation); }
        return refused;
    }
    public boolean ready(ServerLevel level) { return bookings.cancelled(at, id(), generation) || bookings.first(at, id()); }
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (closure.closed()) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (bookings.cancelled(at, id(), generation)) { return Optional.of(CookeryRefusal.STATION_CHANGED); }
        var found = SpitStation.spit(level, part);
        if (found.isEmpty()) { return Optional.of(CookeryRefusal.NOT_A_STATION); }
        var spit = found.get();
        if (!finished && loaded && SpitBatch.read(spit, level).filter(owned -> owned.id().equals(batch) && owned.colony().equals(colony)).isEmpty()) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (!loaded && bookings.first(at, id()) && (!spit.cookingItem.isEmpty() || !spit.cookedItem.isEmpty())) {
            return Optional.of(CookeryRefusal.STATION_BUSY);
        }
        return Optional.empty();
    }

    @Override
    public WorkHolding acquire(HoldingContext context) {
        return new KitchenHolding(this::operation, () -> finished || failed != null,
            this::commit, why -> failed = why);
    }

    private WorkAction operation(ServerLevel level, Worker who) {
        refusal(level).ifPresent(why -> failed = why);
        if (failed != null || finished) { return null; }
        if (!bookings.first(at, id())) { return null; }
        var spit = SpitStation.spit(level, part).orElseThrow();
        if (!loaded && !spit.getBlockState().getValue(ShawarmaSpitBlock.POWERED)
            || loaded && (spit.cookTime > 0 || !spit.cookingItem.isEmpty())) { return null; }
        return new Action(!loaded);
    }
    public Outcome commit(ServerLevel level, Worker who) {
        return finished && !bookings.cancelled(at, id(), generation) ? Outcome.done()
            : Outcome.failed(failed == null ? CookeryRefusal.STATION_CHANGED : failed);
    }

    @Override
    public KitchenClosure closure() {
        return closure;
    }

    private boolean reachable(ServerLevel level, Worker who) {
        if (!KitchenStation.canOperate(level, at, who.body())) { return false; }
        var local = stances instanceof Stances.Cells ? WorkStances.stanceHolding(who.body(), stances)
            : WorldSpaces.relative(level, part.realm(), who.body().position()).map(net.minecraft.core.BlockPos::containing);
        return local
            .flatMap(feet -> WorldSpaces.storage(level, new WorldPos(part.realm(), feet)))
            .filter(feet -> Reach.withinReach(feet, part.block(level)) && Reach.inSight(level, feet, part.block(level))).isPresent();
    }
    private final class Action implements WorkAction {
        private final boolean load;
        private final NodeSpec spec;
        Action(boolean load) {
            this.load = load;
            spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(part), stances, Workload.Once.of(20))
                .needs(load ? List.of(new Need(job.input(), count, true)) : List.of())
                .gives(load ? List.of() : List.of(new Amount(Goods.specOf(job.output()), count)))
                .leaves(load ? List.of() : List.of(new Amount(Goods.specOf(job.output()), count)))
                .tools(load ? List.of() : List.of(new ToolUse(KNIFE, 0))).vocation(WaresContent.trade())
                .gesture(WorkGesture.SWING).focus(part.cell()).doing(load ? CookeryContent.PREPARING : CookeryContent.TAKING).done();
        }
        public NodeSpec spec() { return spec; }
        public Optional<RefusalKind> refusal(ServerLevel level) { return SpitWork.this.refusal(level); }
        public Outcome commit(ServerLevel level, Worker who) {
            if (refusal(level).isPresent() || !bookings.first(at, SpitWork.this.id()) || !reachable(level, who)) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            var spit = SpitStation.spit(level, part).orElseThrow();
            if (load) {
                if (!spit.getBlockState().getValue(ShawarmaSpitBlock.POWERED)) { return Outcome.failed(CookeryRefusal.NO_HEAT_SOURCE); }
                ItemStack first = who.supplied().stream().filter(stack -> Goods.matches(stack, job.input())).findFirst().orElse(ItemStack.EMPTY);
                int supplied = who.supplied().stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, first)).mapToInt(ItemStack::getCount).sum();
                if (supplied < count) { return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH); }
                ItemStack input = first.copyWithCount(count);
                var actual = level.getRecipeManager().getRecipeFor(RecipeType.CAMPFIRE_COOKING, new SingleRecipeInput(input), level);
                if (FolkwaysVanillaConfig.refusedRecipes().contains(job.recipe()) || actual.isEmpty() || !actual.get().id().equals(job.recipe())
                        || actual.get().value().getCookingTime() != job.time()
                        || !ItemStack.matches(actual.get().value().getResultItem(level.registryAccess()).copyWithCount(1), job.output())) {
                    return Outcome.failed(CookeryRefusal.RECIPE_CHANGED);
                }
                ItemStack expected = input.copy();
                try {
                    if (!spit.onPutCookingItem(level, input)) { return Outcome.failed(CookeryRefusal.STATION_CHANGED); }
                } catch (RuntimeException | LinkageError broken) {
                    if (!ItemStack.matches(spit.cookingItem, expected)) { throw broken; }
                    failed = CookeryRefusal.STATION_CHANGED;
                }
                loaded = true;
                SpitBatch.remember(spit, level, new SpitBatch.Batch(colony, batch, job.recipe()));
                changed.run();
                return Outcome.done();
            }
            if (spit.cookTime > 0 || !spit.cookingItem.isEmpty()) { return Outcome.failed(CookeryRefusal.STATION_BUSY); }
            ItemStack knife = who.held(KNIFE);
            if (knife.isEmpty() || !knife.is(TagMod.KITCHEN_KNIFE)) { return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH); }
            var before = spit.saveWithoutMetadata(level.registryAccess());
            KitchenDelivery.Result result;
            try {
                result = KitchenDelivery.run(level, part.block(level), who, knife, () ->
                    SpitSafety.taking(spit, level, who.body(), colony, batch,
                        () -> bookings.valid(at, SpitWork.this.id(), generation), () -> spit.onTakeCookedItem(level, who.body())));
            } catch (ItemDrops.CaptureFailure broken) {
                if (!spit.cookedItem.isEmpty()) {
                    spit.loadWithComponents(before, level.registryAccess());
                    spit.setChanged();
                    return Outcome.failed(CookeryRefusal.STATION_CHANGED);
                }
                result = new KitchenDelivery.Result(true, broken.items());
            }
            if (!result.success()) { return Outcome.failed(CookeryRefusal.STATION_CHANGED); }
            finished = true;
            SpitBatch.clear(spit);
            changed.run();
            return new Outcome.Done(List.of(), result.outputs(), Optional.empty());
        }
    }
}
