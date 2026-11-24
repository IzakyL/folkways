package io.github.izakyl.folkways.vanilla.cookery.tray;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import io.github.izakyl.folkways.core.facility.api.ProcessLoop;
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
import io.github.izakyl.folkways.vanilla.cookery.KitchenDelivery;
import io.github.izakyl.folkways.core.facility.api.ProcessHolding;
import io.github.izakyl.folkways.vanilla.cookery.KitchenWork;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class TrayWork implements KitchenWork {
    private final UUID colony;
    private final UUID batch;
    private final WorldPos at;
    private final int slot;
    private final Stances stances;
    private final TrayStation.Job job;
    private final int count;
    private final KitchenBookings bookings;
    private final long generation;
    private final Runnable changed;
    private final boolean recovering;
    private final Grown recoveryGoal;
    private final NodeSpec spec;
    private Hold hold;
    private final ProcessLoop loop;
    private final KitchenClosure closure;

    TrayWork(UUID colony, WorldPos at, int slot, Stances stances, TrayStation.Job job, int count,
             KitchenBookings bookings, Runnable changed, TrayBatch.Batch previous) {
        this.colony = colony; this.at = at; this.slot = slot; this.stances = stances; this.job = job; this.count = count;
        this.bookings = bookings; generation = bookings.generation(at); this.changed = changed;
        recovering = previous != null; batch = recovering ? previous.id() : UUID.randomUUID();
        loop = new ProcessLoop(new Steps(), recovering);
        List<Amount> output = List.of(new Amount(Goods.specOf(job.output()), (long) count * job.output().getCount()));
        spec = NodeSpec.of(batch, CookeryContent.ID, new WorkSite.AtBlock(at), stances, new Workload.Custom())
            .needs(recovering ? List.of() : List.of(new Need(job.input(), count, true))).gives(output).leaves(output).effort(WorkEffort.NONE)
            .vocation(WaresContent.trade()).estimate(40 + job.time()).doing(CookeryContent.COOKING).done();
        recoveryGoal = recovering ? Grown.of(this) : null;
        closure = new KitchenClosure(how -> {
            if (hold != null) {
                hold.release(how);
                hold = null;
            }
            if (recovering) {
                TrayStation.endRecovery(colony, at, this);
            }
        }, changed);
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
            TrayStation.rememberRecovery(colony, at, this);
        }
        return refused;
    }
    public boolean ready(ServerLevel level) {
        return bookings.cancelled(at, id(), generation) || bookings.first(at, id()) && (loop.isLoaded() || TrayWeather.allows(level, at.block(level), job.subtype()));
    }
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (closure.closed()) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (bookings.cancelled(at, id(), generation)) { return Optional.of(CookeryRefusal.STATION_CHANGED); }
        var tray = TrayStation.tray(level, at);
        if (tray.isEmpty()) { return Optional.of(CookeryRefusal.NOT_A_STATION); }
        if (!loop.isFinished() && loop.isLoaded() && TrayBatch.read(tray.get(), level).filter(owned -> owned.colony().equals(colony) && owned.id().equals(batch)).isEmpty()) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (!loop.isLoaded() && bookings.first(at, id()) && !tray.get().getItem(slot).isEmpty()) { return Optional.of(CookeryRefusal.STATION_BUSY); }
        return Optional.empty();
    }

    @Override
    public WorkHolding acquire(HoldingContext context) {
        return new ProcessHolding(loop::next, loop::over, this::commit, loop::fail);
    }

    // Put in once the weather allows, wait for the tray to finish, take out.
    private final class Steps implements ProcessLoop.Steps {
        public Optional<RefusalKind> refusal(ServerLevel level) { return TrayWork.this.refusal(level); }
        public boolean turn(ServerLevel level) { return bookings.first(at, id()); }
        public boolean loadable(ServerLevel level) { return TrayWeather.allows(level, at.block(level), job.subtype()); }
        public WorkAction load(ServerLevel level, Worker who) { return new Action(true); }
        public boolean outputReady(ServerLevel level) {
            var tray = TrayStation.tray(level, at).orElseThrow();
            return TrayBatch.read(tray, level).filter(owned -> TrayBatch.ready(tray, level, owned)).isPresent();
        }
        public WorkAction take(ServerLevel level, Worker who) { return new Action(false); }
    }
    public Outcome commit(ServerLevel level, Worker who) {
        return loop.isFinished() && !bookings.cancelled(at, id(), generation) ? Outcome.done()
            : Outcome.failed(loop.failure().orElse(CookeryRefusal.STATION_CHANGED));
    }

    @Override
    public KitchenClosure closure() {
        return closure;
    }

    private final class Action implements WorkAction {
        private final boolean load;
        private final NodeSpec spec;
        Action(boolean load) {
            this.load = load;
            List<Amount> output = load ? List.of() : List.of(new Amount(Goods.specOf(job.output()), (long) count * job.output().getCount()));
            spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(at), stances, Workload.Once.of(20))
                .needs(load ? List.of(new Need(job.input(), count, true)) : List.of()).gives(output).leaves(output).vocation(WaresContent.trade())
                .gesture(WorkGesture.SWING).focus(at.cell()).doing(load ? CookeryContent.PREPARING : CookeryContent.TAKING).done();
        }
        public NodeSpec spec() { return spec; }
        public Optional<RefusalKind> refusal(ServerLevel level) { return TrayWork.this.refusal(level); }
        public Outcome commit(ServerLevel level, Worker who) {
            if (refusal(level).isPresent() || !bookings.first(at, TrayWork.this.id())) { return Outcome.failed(CookeryRefusal.STATION_CHANGED); }
            var tray = TrayStation.tray(level, at).orElseThrow();
            if (load) {
                if (!TrayWeather.allows(level, at.block(level), job.subtype())) { return Outcome.failed(CookeryRefusal.STATION_CHANGED); }
                ItemStack first = who.supplied().stream().filter(stack -> Goods.matches(stack, job.input())).findFirst().orElse(ItemStack.EMPTY);
                int supplied = who.supplied().stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, first)).mapToInt(ItemStack::getCount).sum();
                if (supplied < count || count > job.capacity()) { return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH); }
                ItemStack input = first.copyWithCount(count);
                var actual = level.getRecipeManager().getAllRecipesFor(ModRecipes.BAMBOO_TRAY_RECIPE).stream()
                    .filter(recipe -> recipe.value().getSubtype() == job.subtype() && recipe.value().getIngredient().test(input)).findFirst();
                if (FolkwaysVanillaConfig.refusedRecipes().contains(job.recipe()) || actual.isEmpty() || !actual.get().id().equals(job.recipe())
                        || actual.get().value().getDuration() != job.time() || !ItemStack.matches(actual.get().value().getResult(), job.output())) {
                    return Outcome.failed(CookeryRefusal.RECIPE_CHANGED);
                }
                ItemStack expected = input.copy();
                try {
                    if (!tray.onPutItem(level, who.body(), input, slot)) { return Outcome.failed(CookeryRefusal.STATION_CHANGED); }
                } catch (RuntimeException | LinkageError broken) {
                    if (!ItemStack.matches(tray.getItem(slot), expected)) { throw broken; }
                    loop.fail(CookeryRefusal.STATION_CHANGED);
                }
                loop.loaded();
                TrayBatch.remember(tray, level, new TrayBatch.Batch(colony, batch, job.recipe(), slot, job.subtype(), job.time(), expected,
                    job.output().copyWithCount(job.output().getCount() * count)));
                changed.run();
                return Outcome.done();
            }
            var owned = TrayBatch.read(tray, level).orElseThrow();
            if (!TrayBatch.ready(tray, level, owned)) { return Outcome.failed(CookeryRefusal.STATION_BUSY); }
            var before = tray.saveWithoutMetadata(level.registryAccess());
            KitchenDelivery.Result result;
            try { result = KitchenDelivery.run(level, at.block(level), who, ItemStack.EMPTY, () -> tray.onTakeOut(level, who.body(), slot, true)); }
            catch (ItemDrops.CaptureFailure broken) {
                if (!tray.getItem(slot).isEmpty()) {
                    tray.loadWithComponents(before, level.registryAccess()); tray.setChanged();
                    return Outcome.failed(CookeryRefusal.STATION_CHANGED);
                }
                result = new KitchenDelivery.Result(true, broken.items());
            }
            if (!result.success()) { return Outcome.failed(CookeryRefusal.STATION_CHANGED); }
            loop.finished(); TrayBatch.clear(tray); changed.run();
            return new Outcome.Done(List.of(), result.outputs(), Optional.empty());
        }
    }
}
