package io.github.izakyl.folkways.plugins.cookery.millstone;

import com.github.ysbbbbbb.kaleidoscopecookery.crafting.container.SimpleInput;
import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
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
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class MillstoneWork implements Node, Workload.Within {
    private enum Phase { LOAD, DRIVE, TAKE, DONE }
    private final UUID colony;
    private final UUID batch;
    private final WorldPos at;
    private final Stances stances;
    private final MillstoneStation.Job job;
    private final int count;
    private final KitchenBookings bookings;
    private final long generation;
    private final Runnable changed;
    private final NodeSpec spec;
    private final boolean recovering;
    private Hold recoveryHold;
    private Phase phase;
    private boolean loaded;
    private RefusalKind failed;

    MillstoneWork(UUID colony, WorldPos at, Stances stances, MillstoneStation.Job job, int count,
                  KitchenBookings bookings, Runnable changed, MillstoneBatch.Batch previous) {
        this.colony = colony; this.at = at; this.stances = stances; this.job = job; this.count = count;
        this.bookings = bookings; this.generation = bookings.generation(at); this.changed = changed;
        this.batch = previous == null ? UUID.randomUUID() : previous.id();
        this.recovering = previous != null;
        this.loaded = recovering;
        this.phase = previous == null ? Phase.LOAD : Phase.DRIVE;
        this.spec = NodeSpec.of(batch, CookeryContent.ID, new WorkSite.AtBlock(at), stances, this)
            .needs(recovering ? List.of() : List.of(new Need(job.input(), count, true)))
            .gives(job.amounts(count, false)).leaves(job.amounts(count, true)).effort(WorkEffort.NONE)
            .vocation(WaresContent.trade()).gesture(WorkGesture.SWING).focus(at.cell()).estimate(560)
            .doing(CookeryContent.PREPARING).done();
    }
    public NodeSpec spec() { return spec; }
    public Optional<RefusalKind> planned(ServerLevel level) {
        if (!recovering) { return Optional.empty(); }
        if (generation != bookings.generation(at) || !bookings.free(at)) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        recoveryHold = bookings.hold(at, id());
        return recoveryHold.take();
    }
    public boolean ready(ServerLevel level) {
        return bookings.cancelled(at, id(), generation) || bookings.first(at, id()) && MillstoneStation.mill(level, at).isPresent();
    }
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (bookings.cancelled(at, id(), generation)) { return Optional.of(CookeryRefusal.STATION_CHANGED); }
        var mill = MillstoneStation.mill(level, at);
        if (mill.isEmpty()) { return Optional.of(CookeryRefusal.NOT_A_STATION); }
        if (loaded && phase != Phase.DONE && MillstoneBatch.read(mill.get(), level)
                .filter(found -> found.id().equals(batch) && found.colony().equals(colony)).isEmpty()) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (!loaded && phase == Phase.LOAD && bookings.first(at, id()) && (mill.get().hasEntity()
                || !mill.get().getInput().isEmpty() || !mill.get().isOutputEmpty())) {
            return Optional.of(CookeryRefusal.STATION_BUSY);
        }
        return Optional.empty();
    }
    public Turn next(ServerLevel level, Worker who) {
        refusal(level).ifPresent(why -> failed = why);
        if (failed != null || phase == Phase.DONE) { return LEAVE; }
        if (!bookings.first(at, id())) { return WAIT; }
        return new Turn.Piece(phase == Phase.DRIVE ? new Drive() : new Action(phase));
    }
    public void ended(ServerLevel level, Node piece, Ending how) {
        if (how instanceof Ending.Failed failure) { failed = failure.why(); }
        if (how instanceof Ending.Done) {
            phase = switch (phase) { case LOAD -> Phase.DRIVE; case DRIVE -> Phase.TAKE; default -> Phase.DONE; };
        }
    }
    public Outcome commit(ServerLevel level, Worker who) {
        return phase == Phase.DONE && !bookings.cancelled(at, id(), generation) ? Outcome.done()
            : Outcome.failed(failed == null ? CookeryRefusal.STATION_CHANGED : failed);
    }
    public void ended(ServerLevel level, Ending how) {
        if (recoveryHold != null) { recoveryHold.release(how); recoveryHold = null; }
        changed.run();
    }
    private boolean reachable(ServerLevel level, Worker who) {
        return WorldSpaces.relative(level, at.realm(), who.body().position())
            .map(net.minecraft.core.BlockPos::containing)
            .flatMap(feet -> WorldSpaces.storage(level, new WorldPos(at.realm(), feet)))
            .filter(feet -> Reach.withinReach(feet, at.block(level)) && MillstoneStation.inSight(level, feet, at.block(level))).isPresent();
    }
    private NodeSpec actionSpec(UUID id, Workload workload, boolean load) {
        return NodeSpec.of(id, CookeryContent.ID, new WorkSite.AtBlock(at), load ? stances : Stances.WHEREVER, workload)
            .needs(load ? List.of(new Need(job.input(), count, true)) : List.of())
            .gives(load ? List.of() : job.amounts(count, false)).leaves(load ? List.of() : job.amounts(count, true))
            .vocation(WaresContent.trade()).gesture(WorkGesture.SWING).focus(at.cell())
            .doing(CookeryContent.PREPARING).done();
    }
    private class Action implements Node {
        final Phase kind;
        private final NodeSpec spec;
        Action(Phase kind) {
            this.kind = kind;
            spec = actionSpec(UUID.randomUUID(), Workload.Once.of(20), kind == Phase.LOAD);
        }
        public NodeSpec spec() { return spec; }
        public Optional<RefusalKind> refusal(ServerLevel level) { return MillstoneWork.this.refusal(level); }
        public Outcome commit(ServerLevel level, Worker who) {
            if (refusal(level).isPresent() || !bookings.first(at, MillstoneWork.this.id()) || !reachable(level, who)) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            var mill = MillstoneStation.mill(level, at).orElseThrow();
            if (kind == Phase.LOAD) {
                ItemStack input = who.supplied().stream().filter(stack -> Goods.matches(stack, job.input()))
                    .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
                int supplied = who.supplied().stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, input))
                    .mapToInt(ItemStack::getCount).sum();
                if (supplied < count) { return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH); }
                input.setCount(count);
                var recipe = mill.matchRecipe(new SimpleInput(List.of(input)), level);
                if (FolkwaysConfig.refusedRecipes().contains(job.recipe()) || recipe.isEmpty()
                        || !recipe.get().id().equals(job.recipe())) {
                    return Outcome.failed(CookeryRefusal.RECIPE_CHANGED);
                }
                if (!MillstoneDrive.bind(colony, batch, level, at, who.body(), bookings, MillstoneWork.this.id(), generation)) {
                    return Outcome.failed(CookeryRefusal.STATION_BUSY);
                }
                try {
                    if (!mill.onPutItem(level, input)) {
                        MillstoneDrive.interrupt(who.body());
                        return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
                    }
                } catch (RuntimeException | LinkageError broken) {
                    if (mill.getInput().getCount() != count || !Goods.matches(mill.getInput(), job.input())) {
                        MillstoneDrive.interrupt(who.body());
                        throw broken;
                    }
                    // The real mill already owns the ingredients. A failed outcome would refund a second copy.
                    failed = CookeryRefusal.STATION_CHANGED;
                    MillstoneDrive.interrupt(who.body());
                }
                loaded = true;
                MillstoneBatch.remember(mill, level, new MillstoneBatch.Batch(colony, batch, job.recipe(), false));
                changed.run();
                return Outcome.done();
            }
            var owned = MillstoneBatch.read(mill, level).orElseThrow();
            if (!owned.ready() || mill.hasEntity() || MillstoneDrive.exiting(who.body())) {
                return Outcome.failed(CookeryRefusal.STATION_BUSY);
            }
            var before = mill.saveWithoutMetadata(level.registryAccess());
            List<ItemStack> made = new ArrayList<>();
            try {
                for (int slot = 0; slot < mill.getOutputs().getSlots(); slot++) {
                    ItemStack actual = mill.getOutputs().extractItem(slot, Integer.MAX_VALUE, false);
                    if (!actual.isEmpty()) { made.add(actual); }
                }
                mill.resetWhenTakeout();
            } catch (RuntimeException | LinkageError broken) {
                mill.loadWithComponents(before, level.registryAccess());
                mill.setChanged();
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            phase = Phase.DONE;
            MillstoneBatch.clear(mill);
            changed.run();
            return new Outcome.Done(List.of(), made, Optional.empty());
        }
        public void released(ServerLevel level, Worker who, Ending how) {
            if (!(how instanceof Ending.Done)) { MillstoneDrive.interrupt(who.body()); }
        }
    }
    private final class Drive implements Node, Workload.Continuous {
        private final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(at),
            Stances.WHEREVER, this).vocation(WaresContent.trade()).doing(CookeryContent.PREPARING).done();
        public NodeSpec spec() { return spec; }
        public Optional<RefusalKind> refusal(ServerLevel level) { return MillstoneWork.this.refusal(level); }
        public boolean holds(ServerLevel level, Worker who) {
            if (refusal(level).isPresent()) { MillstoneDrive.interrupt(who.body()); return false; }
            var mill = MillstoneStation.mill(level, at).orElseThrow();
            var owned = MillstoneBatch.read(mill, level).orElseThrow();
            if (owned.ready()) { return !MillstoneDrive.leave(level, who.body()); }
            if (MillstoneDrive.exiting(who.body()) && !MillstoneDrive.leave(level, who.body())) { return true; }
            if (!mill.hasEntity() && !MillstoneDrive.bind(colony, batch, level, at, who.body(), bookings, MillstoneWork.this.id(), generation)) {
                failed = CookeryRefusal.STATION_BUSY;
                return false;
            }
            return true;
        }
        public Outcome commit(ServerLevel level, Worker who) {
            return failed == null && refusal(level).isEmpty() && MillstoneStation.mill(level, at)
                    .flatMap(mill -> MillstoneBatch.read(mill, level)).filter(MillstoneBatch.Batch::ready).isPresent()
                    && !MillstoneDrive.exiting(who.body()) ? Outcome.done()
                : Outcome.failed(failed == null ? CookeryRefusal.STATION_CHANGED : failed);
        }
        public void released(ServerLevel level, Worker who, Ending how) {
            if (!(how instanceof Ending.Done)) { MillstoneDrive.interrupt(who.body()); }
        }
    }
}
