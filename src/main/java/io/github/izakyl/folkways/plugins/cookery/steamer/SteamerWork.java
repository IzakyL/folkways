package io.github.izakyl.folkways.plugins.cookery.steamer;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.SteamerBlock;
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
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Leaves;
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
import net.minecraft.world.item.crafting.SingleRecipeInput;

final class SteamerWork implements Node, Workload.Within {
    private final UUID colony;
    private final UUID batch;
    private final WorldPos at;
    private final WorldPos part;
    private final Stances stances;
    private final SteamerStation.Job job;
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

    SteamerWork(UUID colony, WorldPos at, WorldPos part, Stances stances, SteamerStation.Job job, int count,
                KitchenBookings bookings, Runnable changed, SteamerBatch.Batch previous) {
        this.colony = colony; this.at = at; this.part = part; this.stances = stances; this.job = job; this.count = count;
        this.bookings = bookings; this.generation = bookings.generation(at); this.changed = changed;
        recovering = previous != null; loaded = recovering;
        batch = recovering ? previous.id() : UUID.randomUUID();
        List<Amount> output = List.of(new Amount(Goods.specOf(job.output()), (long) count * job.output().getCount()));
        spec = NodeSpec.of(batch, CookeryContent.ID, new WorkSite.AtBlock(at), stances, this)
            .needs(loaded ? List.of() : List.of(new Need(job.input(), count, true))).gives(output).leaves(output)
            .effort(WorkEffort.NONE).vocation(WaresContent.trade()).estimate(40 + Math.max(0, job.time())).doing(CookeryContent.COOKING).done();
        recoveryGoal = recovering ? Grown.of(this) : null;
    }
    Grown recoveryGoal() { return recoveryGoal; }
    public NodeSpec spec() { return spec; }
    @Override
    public List<Amount> planningLeaves(List<Amount> promised) {
        return Leaves.unpromised(spec.leaves(), promised);
    }
    public Optional<RefusalKind> planned(ServerLevel level) {
        if (!recovering) { return Optional.empty(); }
        if (generation != bookings.generation(at) || !bookings.free(at)) { return Optional.of(CookeryRefusal.STATION_CHANGED); }
        hold = bookings.hold(at, id());
        Optional<RefusalKind> refused = hold.take();
        if (refused.isEmpty()) { SteamerStation.keep(colony, at, recoveryGoal, generation); }
        return refused;
    }
    public boolean ready(ServerLevel level) { return bookings.cancelled(at, id(), generation) || bookings.first(at, id()); }
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (bookings.cancelled(at, id(), generation)) { return Optional.of(CookeryRefusal.STATION_CHANGED); }
        var found = SteamerStation.steamer(level, part);
        if (found.isEmpty()) { return Optional.of(CookeryRefusal.NOT_A_STATION); }
        var steamer = found.get();
        if (!finished && loaded && SteamerBatch.read(steamer, level).filter(owned -> owned.colony().equals(colony) && owned.id().equals(batch)).isEmpty()) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (!loaded && bookings.first(at, id()) && steamer.getItems().stream().anyMatch(stack -> !stack.isEmpty())) {
            return Optional.of(CookeryRefusal.STATION_BUSY);
        }
        return Optional.empty();
    }
    public Turn next(ServerLevel level, Worker who) {
        refusal(level).ifPresent(why -> failed = why);
        if (failed != null || finished) { return LEAVE; }
        if (!bookings.first(at, id())) { return WAIT; }
        var steamer = SteamerStation.steamer(level, part).orElseThrow();
        if (!loaded && !SteamerStation.heat(level, part.block(level)) || loaded && !SteamerStation.ripe(steamer)) { return WAIT; }
        return new Turn.Piece(new Action(!loaded));
    }
    public void ended(ServerLevel level, Node piece, Ending how) {
        if (how instanceof Ending.Failed failure) { failed = failure.why(); }
    }
    public Outcome commit(ServerLevel level, Worker who) {
        return finished && !bookings.cancelled(at, id(), generation) ? Outcome.done()
            : Outcome.failed(failed == null ? CookeryRefusal.STATION_CHANGED : failed);
    }
    public void ended(ServerLevel level, Ending how) {
        if (recovering) { SteamerStation.ended(colony, at, this); }
        if (hold != null) { hold.release(how); hold = null; }
        changed.run();
    }
    private boolean reachable(ServerLevel level, Worker who, BlockPos target) {
        var local = stances instanceof Stances.Cells ? Reach.stanceHolding(who.body(), stances)
            : WorldSpaces.relative(level, part.realm(), who.body().position()).map(net.minecraft.core.BlockPos::containing);
        return local
            .flatMap(feet -> WorldSpaces.storage(level, new WorldPos(part.realm(), feet)))
            .filter(feet -> Reach.withinReach(feet, target) && Reach.inSight(level, feet, target)).isPresent();
    }
    private final class Action implements Node {
        private final boolean load;
        private final NodeSpec spec;
        Action(boolean load) {
            this.load = load;
            List<Amount> output = load ? List.of() : List.of(new Amount(Goods.specOf(job.output()), (long) count * job.output().getCount()));
            spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(part), stances, Workload.Once.of(20))
                .needs(load ? List.of(new Need(job.input(), count, true)) : List.of()).gives(output).leaves(output)
                .vocation(WaresContent.trade()).gesture(WorkGesture.SWING).focus(part.cell())
                .doing(load ? CookeryContent.PREPARING : CookeryContent.TAKING).done();
        }
        public NodeSpec spec() { return spec; }
        public Optional<RefusalKind> refusal(ServerLevel level) { return SteamerWork.this.refusal(level); }
        public Outcome commit(ServerLevel level, Worker who) {
            if (refusal(level).isPresent() || !bookings.first(at, SteamerWork.this.id()) || !reachable(level, who, part.block(level))) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            var steamer = SteamerStation.steamer(level, part).orElseThrow();
            if (!SteamerStation.accessible(level, part.block(level))) { return Outcome.failed(CookeryRefusal.STATION_BUSY); }
            if (load) {
                if (!SteamerStation.heat(level, part.block(level))) { return Outcome.failed(CookeryRefusal.NO_HEAT_SOURCE); }
                ItemStack first = who.supplied().stream().filter(stack -> Goods.matches(stack, job.input())).findFirst().orElse(ItemStack.EMPTY);
                int supplied = who.supplied().stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, first)).mapToInt(ItemStack::getCount).sum();
                int capacity = steamer.getBlockState().getValue(SteamerBlock.HALF) ? 4 : 8;
                if (supplied < count || count > capacity) { return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH); }
                ItemStack input = first.copyWithCount(count);
                var actual = level.getRecipeManager().getRecipeFor(ModRecipes.STEAMER_RECIPE, new SingleRecipeInput(input), level);
                if (FolkwaysConfig.refusedRecipes().contains(job.recipe()) || actual.isEmpty() || !actual.get().id().equals(job.recipe())
                        || actual.get().value().getCookTick() != job.time()
                        || !ItemStack.matches(actual.get().value().assemble(new SingleRecipeInput(input.copyWithCount(1)), level.registryAccess()), job.output())) {
                    return Outcome.failed(CookeryRefusal.RECIPE_CHANGED);
                }
                var parts = SteamerStation.parts(level, at);
                BlockPos top = parts.getLast().block(level);
                if (!reachable(level, who, top)) { return Outcome.failed(CookeryRefusal.STATION_CHANGED); }
                SteamerLids.Cap cap = SteamerLids.close(level, top);
                boolean success;
                try { success = steamer.placeFood(level, who.body(), input); }
                catch (RuntimeException | LinkageError broken) {
                    if (input.getCount() == count) { SteamerLids.restore(level, cap); throw broken; }
                    success = true;
                    failed = CookeryRefusal.STATION_CHANGED;
                }
                if (!success || input.getCount() == count) {
                    SteamerLids.restore(level, cap);
                    return Outcome.failed(CookeryRefusal.STATION_CHANGED);
                }
                loaded = true;
                if (!input.isEmpty()) { failed = CookeryRefusal.STATION_CHANGED; }
                SteamerBatch.remember(steamer, level, at, new SteamerBatch.Batch(colony, batch, job.recipe()), job.output(), job.time(), cap);
                changed.run();
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), input.isEmpty() ? List.of() : List.of(input));
            }
            if (!SteamerStation.ripe(steamer)) { return Outcome.failed(CookeryRefusal.STATION_BUSY); }
            var before = steamer.saveWithoutMetadata(level.registryAccess());
            KitchenDelivery.Result result;
            try { result = KitchenDelivery.run(level, part.block(level), who, ItemStack.EMPTY, () -> steamer.takeFood(level, who.body())); }
            catch (ItemDrops.CaptureFailure broken) {
                if (steamer.getItems().stream().anyMatch(stack -> !stack.isEmpty())) {
                    steamer.loadWithComponents(before, level.registryAccess());
                    steamer.setChanged();
                    return Outcome.failed(CookeryRefusal.STATION_CHANGED);
                }
                result = new KitchenDelivery.Result(true, broken.items());
            }
            if (!result.success()) { return Outcome.failed(CookeryRefusal.STATION_CHANGED); }
            finished = true;
            SteamerBatch.restoreLid(steamer, level);
            SteamerBatch.clear(steamer);
            changed.run();
            return new Outcome.Done(List.of(), result.outputs(), Optional.empty());
        }
    }
}
