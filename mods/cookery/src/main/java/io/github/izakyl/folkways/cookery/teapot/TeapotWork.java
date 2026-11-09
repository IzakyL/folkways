package io.github.izakyl.folkways.cookery.teapot;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.ITeapot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.TeapotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.item.TeapotItem;
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
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
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
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class TeapotWork implements KitchenWork {
    private enum Kind { FLUID, INGREDIENT, SERVE }
    private final UUID colony;
    private final UUID batch;
    private final WorldPos at;
    private final Stances stances;
    private final TeapotStation.Job job;
    private final KitchenBookings bookings;
    private final long generation;
    private final boolean recovering;
    private final Grown recoveryGoal;
    private final NodeSpec spec;
    private Hold hold;
    private boolean fluid;
    private boolean ingredient;
    private boolean finished;
    private RefusalKind failed;
    private final KitchenClosure closure;

    TeapotWork(UUID colony, WorldPos at, Stances stances, TeapotStation.Job job, KitchenBookings bookings,
               Runnable changed, TeaBatch.Batch previous) {
        this.colony = colony; this.at = at; this.stances = stances; this.job = job; this.bookings = bookings;
        generation = bookings.generation(at); recovering = previous != null;
        fluid = recovering; ingredient = recovering && previous.loaded(); batch = recovering ? previous.id() : UUID.randomUUID();
        List<Need> needs = new ArrayList<>();
        if (!fluid) { needs.add(new Need(Goods.specOf(job.bucket()), 1, true)); }
        if (!ingredient) { needs.add(new Need(job.ingredient(), 1, true)); }
        needs.add(new Need(ItemSpec.of(ModItems.EMPTY_CUP.get()), job.output().getCount(), true));
        List<Amount> leaves = new ArrayList<>(List.of(new Amount(Goods.specOf(job.output()), job.output().getCount())));
        if (!fluid) { leaves.add(new Amount(Goods.specOf(new ItemStack(Items.BUCKET)), 1)); }
        spec = NodeSpec.of(batch, CookeryContent.ID, new WorkSite.AtBlock(at), stances,
            new Workload.Custom()).needs(needs)
            .gives(new Amount(Goods.specOf(job.output()), job.output().getCount())).leaves(leaves).effort(WorkEffort.NONE)
            .vocation(WaresContent.trade()).estimate(260 + job.time()).doing(CookeryContent.COOKING).done();
        recoveryGoal = recovering ? Grown.of(this) : null;
        closure = new KitchenClosure(how -> {
            if (hold != null) {
                hold.release(how);
                hold = null;
            }
            if (recovering) {
                TeapotStation.endRecovery(colony, at, this);
            }
        }, changed, "tea refresh failed after settlement");
    }

    @Override
    public boolean producesBeforeSettlement() {
        return true;
    }

    public NodeSpec spec() { return spec; }

    @Override
    public List<Amount> planningLeaves(List<Amount> promised) {
        return Leaves.unpromised(spec.leaves(), promised);
    }
    Grown recoveryGoal() { return recoveryGoal; }
    public Optional<RefusalKind> planned(ServerLevel level) {
        if (!recovering) { return Optional.empty(); }
        if (hold != null) {
            return bookings.valid(at, id(), generation) ? Optional.empty()
                : Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (generation != bookings.generation(at) || !bookings.free(at)) { return Optional.of(CookeryRefusal.STATION_CHANGED); }
        hold = bookings.hold(at, id(), spec().estimate());
        var refused = hold.take();
        if (refused.isEmpty()) {
            TeapotStation.rememberRecovery(colony, at, this);
        }
        return refused;
    }
    public boolean ready(ServerLevel level) { return bookings.cancelled(at, id(), generation) || bookings.first(at, id()); }
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (closure.closed()) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (bookings.cancelled(at, id(), generation)) { return Optional.of(CookeryRefusal.STATION_CHANGED); }
        var pot = TeapotStation.pot(level, at);
        if (pot.isEmpty()) { return Optional.of(CookeryRefusal.NOT_A_STATION); }
        if (!finished && fluid && TeaBatch.read(pot.get(), level).filter(owned -> owned.colony().equals(colony) && owned.id().equals(batch)).isEmpty()) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (!fluid && bookings.first(at, id()) && !TeapotStation.empty(pot.get())) { return Optional.of(CookeryRefusal.STATION_BUSY); }
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
        var pot = TeapotStation.pot(level, at).orElseThrow();
        if (!fluid || !ingredient) { return new Action(!fluid ? Kind.FLUID : Kind.INGREDIENT); }
        if (pot.getStatus() != ITeapot.FINISHED) { return null; }
        if (cupPlace(level, who).isEmpty()) {
            // All tables may be occupied temporarily. Keep the real finished tea and wait for a space.
            // If another stance can serve it now, release this old placement so recovery publishes that stance.
            if (new TeapotStation().stances(level, at).isPresent()) {
                failed = CookeryRefusal.STATION_BUSY;
                return null;
            }
            return null;
        }
        return new Action(Kind.SERVE);
    }
    public Outcome commit(ServerLevel level, Worker who) {
        return finished && !bookings.cancelled(at, id(), generation) ? Outcome.done() : Outcome.failed(failed == null ? CookeryRefusal.STATION_CHANGED : failed);
    }

    @Override
    public KitchenClosure closure() {
        return closure;
    }

    private void nudge() {
        closure.refresh();
    }
    private Optional<BlockPos> feet(ServerLevel level, Worker who) {
        var local = stances instanceof Stances.Cells ? WorkStances.stanceHolding(who.body(), stances)
            : WorldSpaces.relative(level, at.realm(), who.body().position()).map(BlockPos::containing);
        return local
            .flatMap(footing -> WorldSpaces.storage(level, new WorldPos(at.realm(), footing)));
    }
    private boolean reachable(ServerLevel level, Worker who, BlockPos target) {
        if (!KitchenStation.canOperate(level, at, who.body())) { return false; }
        return feet(level, who).filter(feet -> Reach.withinReach(feet, target) && Reach.inSight(level, feet, target)).isPresent();
    }
    private Optional<BlockPos> cupPlace(ServerLevel level, Worker who) {
        for (BlockPos cell : TeaCups.places(level, at.block(level))) {
            if (feet(level, who).filter(feet -> !feet.equals(cell)).isPresent() && reachable(level, who, cell)) { return Optional.of(cell); }
        }
        return Optional.empty();
    }
    private void restore(ServerLevel level, BlockState state, CompoundTag data) {
        if (!level.getBlockState(at.block(level)).equals(state)) { level.setBlockAndUpdate(at.block(level), state); }
        TeapotBlockEntity restored = TeapotStation.pot(level, at).orElseThrow();
        restored.loadWithComponents(data, level.registryAccess()); restored.setChanged();
    }
    private final class Action implements WorkAction {
        private final Kind kind;
        private final NodeSpec spec;
        Action(Kind kind) {
            this.kind = kind;
            Need need =
                switch (kind) {
                    case FLUID -> new Need(Goods.specOf(job.bucket()), 1, true);
                    case INGREDIENT -> new Need(job.ingredient(), 1, true);
                    case SERVE -> new Need(ItemSpec.of(ModItems.EMPTY_CUP.get()), job.output().getCount(), true);
                };
            List<Amount> made = kind == Kind.SERVE ? List.of(new Amount(Goods.specOf(job.output()), job.output().getCount())) : List.of();
            List<Amount> leaves = kind == Kind.FLUID ? List.of(new Amount(Goods.specOf(new ItemStack(Items.BUCKET)), 1)) : made;
            spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(at), stances,
                Workload.Once.of(kind == Kind.SERVE ? 20 * job.output().getCount() : 20)).needs(need).gives(made).leaves(leaves)
                .vocation(WaresContent.trade()).gesture(WorkGesture.SWING).focus(at.cell())
                .doing(kind == Kind.SERVE ? CookeryContent.TAKING : CookeryContent.PREPARING).done();
        }
        public NodeSpec spec() { return spec; }
        public Optional<RefusalKind> refusal(ServerLevel level) { return TeapotWork.this.refusal(level); }
        public Outcome commit(ServerLevel level, Worker who) {
            if (refusal(level).isPresent() || !bookings.first(at, TeapotWork.this.id()) || !reachable(level, who, at.block(level))) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            var pot = TeapotStation.pot(level, at).orElseThrow();
            if (kind != Kind.SERVE) {
                ItemStack supplied = who.supplied().stream().filter(stack -> Goods.matches(stack, spec.needs().getFirst().spec())).findFirst()
                    .map(stack -> stack.copyWithCount(1)).orElse(ItemStack.EMPTY);
                if (supplied.isEmpty()) { return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH); }
                ItemStack input = kind == Kind.INGREDIENT ? supplied.copy() : job.input();
                if (FolkwaysVanillaConfig.refusedRecipes().contains(job.recipe()) || !job.selected(level, input)) {
                    return Outcome.failed(CookeryRefusal.RECIPE_CHANGED);
                }
                if (kind == Kind.FLUID
                    && job.mode() == TeapotStation.Mode.MYSTERY
                    && (Goods.countIn(who.pack(), ItemSpec.of(ModItems.EMPTY_CUP.get())) < job.output().getCount()
                    || Goods.countIn(who.pack(), job.ingredient()) < 1)) {
                    return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
                }
                BlockState state = pot.getBlockState(); CompoundTag before = pot.saveWithoutMetadata(level.registryAccess());
                KitchenDelivery.Result result;
                try {
                    result = KitchenDelivery.run(level, at.block(level), who, supplied, () -> kind == Kind.FLUID
                        ? pot.addTeaFluid(level, who.body(), supplied) : pot.addIngredient(level, who.body(), supplied));
                } catch (ItemDrops.CaptureFailure broken) {
                    restore(level, state, before);
                    return Outcome.failed(CookeryRefusal.STATION_CHANGED);
                }
                if (!result.success()) { restore(level, state, before); return Outcome.failed(CookeryRefusal.STATION_CHANGED); }
                fluid = true;
                if (kind == Kind.INGREDIENT) { ingredient = true; }
                TeaBatch.remember(pot, level, new TeaBatch.Batch(colony, batch, job.recipe(), job.fluid(), input, job.output(), job.time(), ingredient, job.mode()));
                nudge();
                return new Outcome.Done(List.of(), result.outputs(), Optional.empty());
            }
            if (pot.getStatus() != ITeapot.FINISHED || !ItemStack.matches(pot.getResult(), job.output())) { return Outcome.failed(CookeryRefusal.STATION_BUSY); }
            List<ItemStack> cups = new ArrayList<>();
            for (ItemStack supply : who.supplied()) {
                if (!supply.is(ModItems.EMPTY_CUP.get())) { continue; }
                for (int count = 0; count < supply.getCount(); count++) { cups.add(supply.copyWithCount(1)); }
            }
            if (cups.size() != job.output().getCount()) { return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH); }
            Optional<BlockPos> position = cupPlace(level, who);
            if (position.isEmpty()) { return Outcome.failed(CookeryRefusal.STATION_BUSY); }
            BlockPos cup = position.get();
            BlockState state = pot.getBlockState(); CompoundTag before = pot.saveWithoutMetadata(level.registryAccess());
            KitchenDelivery.Result result;
            try {
                result = KitchenDelivery.run(level, at.block(level), who, ItemStack.EMPTY, () -> {
                    if (!pot.takeTeapot(level, who.body())) { return false; }
                    ItemStack actualPot = who.body().getMainHandItem();
                    if (!actualPot.is(ModItems.TEAPOT.get()) || !ItemStack.matches(TeapotItem.getPourOut(actualPot, level), job.output())) { return false; }
                    for (ItemStack actualCup : cups) {
                        if (!TeaCups.place(level, cup, actualCup) || !TeaCups.pour(level, cup, actualPot) || !TeaCups.take(level, cup, who.body())) { return false; }
                    }
                    if (!TeapotItem.getPourOut(actualPot, level).isEmpty() || !TeaCups.place(level, at.block(level), actualPot)) { return false; }
                    // Keep the original placement properties after replacing the one real, now-empty pot.
                    level.setBlockAndUpdate(at.block(level), state);
                    return TeapotStation.pot(level, at).filter(TeapotStation::empty).isPresent() && actualPot.isEmpty();
                });
            } catch (ItemDrops.CaptureFailure broken) {
                level.setBlockAndUpdate(cup, Blocks.AIR.defaultBlockState()); restore(level, state, before);
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            if (!result.success()) {
                level.setBlockAndUpdate(cup, Blocks.AIR.defaultBlockState()); restore(level, state, before);
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            finished = true; nudge();
            return new Outcome.Done(List.of(), result.outputs(), Optional.empty());
        }
    }
}
