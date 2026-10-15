package io.github.izakyl.folkways.plugins.cookery.tray;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemDrops;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.CookeryRefusal;
import io.github.izakyl.folkways.plugins.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.cookery.KitchenDelivery;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class TrayWork implements Node, Workload.Within {
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
    private final NodeSpec spec;
    private Hold hold;
    private boolean loaded;
    private boolean finished;
    private RefusalKind failed;

    TrayWork(UUID colony, WorldPos at, int slot, Stances stances, TrayStation.Job job, int count,
             KitchenBookings bookings, Runnable changed, TrayBatch.Batch previous) {
        this.colony = colony; this.at = at; this.slot = slot; this.stances = stances; this.job = job; this.count = count;
        this.bookings = bookings; generation = bookings.generation(at); this.changed = changed;
        recovering = previous != null; loaded = recovering; batch = recovering ? previous.id() : UUID.randomUUID();
        List<Amount> output = List.of(new Amount(Goods.specOf(job.output()), (long) count * job.output().getCount()));
        spec = NodeSpec.of(batch, CookeryContent.ID, new WorkSite.AtBlock(at), stances, this)
            .needs(loaded ? List.of() : List.of(new Need(job.input(), count, true))).gives(output).leaves(output).effort(WorkEffort.NONE)
            .vocation(WaresContent.trade()).estimate(40 + job.time()).doing(CookeryContent.COOKING).done();
    }
    public NodeSpec spec() { return spec; }
    public Optional<RefusalKind> planned(ServerLevel level) {
        if (!recovering) { return Optional.empty(); }
        if (generation != bookings.generation(at) || !bookings.free(at)) { return Optional.of(CookeryRefusal.STATION_CHANGED); }
        hold = bookings.hold(at, id()); return hold.take();
    }
    public boolean ready(ServerLevel level) {
        return bookings.cancelled(at, id(), generation) || bookings.first(at, id()) && (loaded || TrayWeather.allows(level, at.block(level), job.subtype()));
    }
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (bookings.cancelled(at, id(), generation)) { return Optional.of(CookeryRefusal.STATION_CHANGED); }
        var tray = TrayStation.tray(level, at);
        if (tray.isEmpty()) { return Optional.of(CookeryRefusal.NOT_A_STATION); }
        if (!finished && loaded && TrayBatch.read(tray.get(), level).filter(owned -> owned.colony().equals(colony) && owned.id().equals(batch)).isEmpty()) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (!loaded && bookings.first(at, id()) && !tray.get().getItem(slot).isEmpty()) { return Optional.of(CookeryRefusal.STATION_BUSY); }
        return Optional.empty();
    }
    public Turn next(ServerLevel level, Worker who) {
        refusal(level).ifPresent(why -> failed = why);
        if (failed != null || finished) { return LEAVE; }
        if (!bookings.first(at, id())) { return WAIT; }
        var tray = TrayStation.tray(level, at).orElseThrow();
        if (!loaded && !TrayWeather.allows(level, at.block(level), job.subtype())
                || loaded && TrayBatch.read(tray, level).filter(owned -> TrayBatch.ready(tray, level, owned)).isEmpty()) { return WAIT; }
        return new Turn.Piece(new Action(!loaded));
    }
    public void ended(ServerLevel level, Node piece, Ending how) { if (how instanceof Ending.Failed failure) { failed = failure.why(); } }
    public Outcome commit(ServerLevel level, Worker who) {
        return finished && !bookings.cancelled(at, id(), generation) ? Outcome.done() : Outcome.failed(failed == null ? CookeryRefusal.STATION_CHANGED : failed);
    }
    public void ended(ServerLevel level, Ending how) { if (hold != null) { hold.release(how); hold = null; } changed.run(); }
    private boolean reachable(ServerLevel level, Worker who) {
        var local = stances instanceof Stances.Cells ? Reach.stanceHolding(who.body(), stances)
            : WorldSpaces.relative(level, at.realm(), who.body().position()).map(net.minecraft.core.BlockPos::containing);
        return local
            .flatMap(feet -> WorldSpaces.storage(level, new WorldPos(at.realm(), feet)))
            .filter(feet -> Reach.withinReach(feet, at.block(level)) && Reach.inSight(level, feet, at.block(level))).isPresent();
    }
    private final class Action implements Node {
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
            if (refusal(level).isPresent() || !bookings.first(at, TrayWork.this.id()) || !reachable(level, who)) { return Outcome.failed(CookeryRefusal.STATION_CHANGED); }
            var tray = TrayStation.tray(level, at).orElseThrow();
            if (load) {
                if (!TrayWeather.allows(level, at.block(level), job.subtype())) { return Outcome.failed(CookeryRefusal.STATION_CHANGED); }
                ItemStack first = who.supplied().stream().filter(stack -> Goods.matches(stack, job.input())).findFirst().orElse(ItemStack.EMPTY);
                int supplied = who.supplied().stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, first)).mapToInt(ItemStack::getCount).sum();
                if (supplied < count || count > job.capacity()) { return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH); }
                ItemStack input = first.copyWithCount(count);
                var actual = level.getRecipeManager().getAllRecipesFor(ModRecipes.BAMBOO_TRAY_RECIPE).stream()
                    .filter(recipe -> recipe.value().getSubtype() == job.subtype() && recipe.value().getIngredient().test(input)).findFirst();
                if (FolkwaysConfig.refusedRecipes().contains(job.recipe()) || actual.isEmpty() || !actual.get().id().equals(job.recipe())
                        || actual.get().value().getDuration() != job.time() || !ItemStack.matches(actual.get().value().getResult(), job.output())) {
                    return Outcome.failed(CookeryRefusal.RECIPE_CHANGED);
                }
                ItemStack expected = input.copy();
                try {
                    if (!tray.onPutItem(level, who.body(), input, slot)) { return Outcome.failed(CookeryRefusal.STATION_CHANGED); }
                } catch (RuntimeException | LinkageError broken) {
                    if (!ItemStack.matches(tray.getItem(slot), expected)) { throw broken; }
                    failed = CookeryRefusal.STATION_CHANGED;
                }
                loaded = true;
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
            finished = true; TrayBatch.clear(tray); changed.run();
            return new Outcome.Done(List.of(), result.outputs(), Optional.empty());
        }
    }
}
