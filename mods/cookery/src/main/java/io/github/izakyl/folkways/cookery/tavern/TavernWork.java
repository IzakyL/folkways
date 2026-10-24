package io.github.izakyl.folkways.cookery.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.DrinkBlockEntity;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModItems;
import io.github.izakyl.folkways.core.api.terms.*;
import io.github.izakyl.folkways.core.api.work.*;
import io.github.izakyl.folkways.cookery.*;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class TavernWork implements Node, Workload.Within {
    private final UUID colony, batch;
    private final WorldPos at;
    private final Stances stances;
    private final KitchenBookings bookings;
    private final Runnable changed;
    private final boolean barrel, recovering;
    private final long generation;
    private final NodeSpec spec;
    private final Grown recovery;
    private Hold hold;
    private boolean loaded, finished;
    private int remaining, bottle;
    private RefusalKind failed;
    TavernWork(UUID colony, WorldPos at, Stances stances, KitchenBookings bookings, Runnable changed, boolean barrel, TavernBatch.Batch previous) {
        this.colony = colony; this.at = at; this.stances = stances; this.bookings = bookings; this.changed = changed; this.barrel = barrel;
        recovering = previous != null; loaded = recovering; batch = recovering ? previous.id() : UUID.randomUUID();
        generation = bookings.generation(at);
        remaining = previous == null ? barrel ? 16 : 1 : previous.remaining();
        bottle = previous == null ? 0 : previous.bottle();
        var needs = new ArrayList<Need>();
        if (!loaded) needs.add(new Need(input(), barrel ? 4 : 8, true));
        if (remaining - (bottle > 0 ? 1 : 0) > 0) needs.add(new Need(carrier(), remaining - (bottle > 0 ? 1 : 0), true));
        var leaves = new ArrayList<Amount>();
        leaves.add(new Amount(Goods.specOf(TavernStation.output(barrel)), remaining));
        if (barrel && !loaded) leaves.add(new Amount(Goods.specOf(new ItemStack(Items.BUCKET)), 4));
        spec = NodeSpec.of(batch, CookeryContent.ID, new WorkSite.AtBlock(at), stances, this)
            .needs(needs).gives(new Amount(Goods.specOf(TavernStation.output(barrel)), remaining)).leaves(leaves)
            .effort(WorkEffort.NONE).vocation(WaresContent.trade()).estimate(barrel ? 3600 : 400)
            .doing(CookeryContent.COOKING).done();
        recovery = recovering ? Grown.of(this) : null;
    }
    private ItemSpec input() { return ItemSpec.of(barrel ? ModItems.GRAPE_BUCKET.get() : ModItems.GRAPE.get()); }
    private ItemSpec carrier() { return ItemSpec.of(barrel ? ModItems.EMPTY_BOTTLE.get() : Items.BUCKET); }
    public NodeSpec spec() { return spec; }
    public List<Amount> planningLeaves(List<Amount> promised) { return Leaves.unpromised(spec.leaves(), promised); }
    public Optional<RefusalKind> planned(ServerLevel level) {
        if (!recovering) return Optional.empty();
        if (hold != null) return bookings.valid(at, id(), generation) ? Optional.empty() : Optional.of(CookeryRefusal.STATION_CHANGED);
        if (!bookings.free(at) || bookings.generation(at) != generation) return Optional.of(CookeryRefusal.STATION_CHANGED);
        hold = bookings.hold(at, id(), spec.estimate()); var refused = hold.take();
        if (refused.isEmpty()) TavernStation.keep(colony, at, recovery);
        return refused;
    }
    public boolean ready(ServerLevel level) { return bookings.cancelled(at, id(), generation) || bookings.first(at, id()); }
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (bookings.cancelled(at, id(), generation)) return Optional.of(CookeryRefusal.STATION_CHANGED);
        if (!TavernStation.recipeValid(level, barrel)) return Optional.of(CookeryRefusal.RECIPE_CHANGED);
        if (barrel ? TavernStation.barrel(level, at).isEmpty() || TavernStation.tap(level, at).isEmpty() : TavernStation.tub(level, at).isEmpty()) return Optional.of(CookeryRefusal.NOT_A_STATION);
        if (!finished && loaded && TavernBatch.read(level, at, barrel).filter(b -> b.colony().equals(colony) && b.id().equals(batch)).isEmpty()) return Optional.of(CookeryRefusal.STATION_CHANGED);
        if (!loaded && bookings.first(at, id()) && !TavernStation.empty(level, at, barrel)) return Optional.of(CookeryRefusal.STATION_BUSY);
        return Optional.empty();
    }
    public Turn next(ServerLevel level, Worker who) {
        refusal(level).ifPresent(why -> failed = why);
        if (failed != null || finished) return LEAVE;
        if (!bookings.first(at, id())) return WAIT;
        if (!loaded) {
            var working = barrel ? TavernStation.lid(level, at) : Optional.of(stances);
            if (working.isEmpty()) { failed = CookeryRefusal.STATION_CHANGED; return LEAVE; }
            return new Turn.Piece(new Action(true, working.get()));
        }
        if (barrel) return bottle > 0 || TavernStation.barrel(level, at).filter(b -> b.getBrewLevel() >= 2).isPresent() ? new Turn.Piece(new Bottle(bottle)) : WAIT;
        return TavernStation.tub(level, at).filter(t -> t.getFluidAmount() == 1000).isPresent()
            ? new Turn.Piece(new Action(false, stances)) : new Turn.Piece(new Press());
    }
    public void ended(ServerLevel level, Node piece, Ending how) { if (how instanceof Ending.Failed f) failed = f.why(); }
    public Outcome commit(ServerLevel level, Worker who) { return finished ? Outcome.done() : Outcome.failed(failed == null ? CookeryRefusal.STATION_CHANGED : failed); }
    public void ended(ServerLevel level, Ending how) {
        TavernStation.forget(colony, at, this);
        if (hold != null) { hold.release(how); hold = null; }
        changed.run();
    }
    private boolean reachable(ServerLevel level, Worker who, Stances working, BlockPos target) {
        return Reach.stanceHolding(who.body(), working).flatMap(p -> WorldSpaces.storage(level, new WorldPos(at.realm(), p)))
            .filter(p -> Reach.withinReach(p, target) && Reach.inSight(level, p, target)).isPresent();
    }
    private ItemStack supplied(Worker who, ItemSpec wanted, int count) {
        ItemStack one = who.supplied().stream().filter(s -> Goods.matches(s, wanted)).findFirst().orElse(ItemStack.EMPTY);
        return who.supplied().stream().filter(s -> ItemStack.isSameItemSameComponents(s, one)).mapToInt(ItemStack::getCount).sum() >= count ? one.copyWithCount(count) : ItemStack.EMPTY;
    }
    private final class Press implements Node, Workload.Continuous {
        private final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(at), stances, this)
            .vocation(WaresContent.trade()).doing(CookeryContent.COOKING).focus(at.cell()).done();
        public NodeSpec spec() { return spec; }
        public Optional<RefusalKind> refusal(ServerLevel level) { return TavernWork.this.refusal(level); }
        public boolean holds(ServerLevel level, Worker who) {
            if (refusal(level).isPresent()) return false;
            var tub = TavernStation.tub(level, at).orElseThrow();
            if (tub.getFluidAmount() >= 1000) return false;
            var body = who.body();
            Vec3 center = Vec3.atBottomCenterOf(at.block(level));
            // Normal JumpControl causes an actual jump and native tub fallOn handles every landing.
            if (body.position().subtract(center).horizontalDistanceSqr() < 0.16 && body.onGround()) body.getJumpControl().jump();
            else if (body.onGround()) body.getMoveControl().setWantedPosition(center.x, body.getY(), center.z, 0.5);
            return true;
        }
        public Outcome commit(ServerLevel level, Worker who) {
            return refusal(level).isEmpty() && TavernStation.tub(level, at).filter(t -> t.getFluidAmount() == 1000).isPresent()
                ? Outcome.done() : Outcome.failed(CookeryRefusal.STATION_CHANGED);
        }
    }
    private final class Action implements Node {
        private final boolean load;
        private final NodeSpec spec;
        Action(boolean load, Stances working) {
            this.load = load;
            var needs = load ? List.of(new Need(input(), barrel ? 4 : 8, true)) : List.of(new Need(carrier(), barrel ? 16 : 1, true));
            var leaves = load ? barrel ? List.of(new Amount(Goods.specOf(new ItemStack(Items.BUCKET)), 4)) : List.<Amount>of()
                : List.of(new Amount(Goods.specOf(TavernStation.output(barrel)), remaining));
            spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(at), working, Workload.Once.of(load ? 40 : 100))
                .needs(needs).gives(load ? List.of() : leaves).leaves(leaves).vocation(WaresContent.trade())
                .gesture(WorkGesture.SWING).focus(at.cell()).doing(load ? CookeryContent.PREPARING : CookeryContent.TAKING).done();
        }
        public NodeSpec spec() { return spec; }
        public Optional<RefusalKind> refusal(ServerLevel level) { return TavernWork.this.refusal(level); }
        public Outcome commit(ServerLevel level, Worker who) {
            BlockPos target = barrel ? at.block(level).above(2) : at.block(level);
            if (refusal(level).isPresent() || !bookings.first(at, TavernWork.this.id()) || !reachable(level, who, spec.stances(), target)) return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            if (load) return load(level, who);
            return takeJuice(level, who);
        }
        private Outcome load(ServerLevel level, Worker who) {
            ItemStack input = supplied(who, input(), barrel ? 4 : 8);
            if (input.isEmpty()) return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
            var be = level.getBlockEntity(at.block(level));
            var before = be.saveWithoutMetadata(level.registryAccess());
            KitchenDelivery.Result delivery;
            try {
                delivery = KitchenDelivery.run(level, at.block(level), who, input, () -> {
                    if (!barrel) return TavernStation.tub(level, at).orElseThrow().addIngredient(input);
                    var nativeBarrel = TavernStation.barrel(level, at).orElseThrow();
                    if (!nativeBarrel.openLid(who.body())) return false;
                    for (int i = 0; i < 4; i++) if (!nativeBarrel.addFluid(who.body(), input)) return false;
                    return nativeBarrel.closeLid(who.body());
                });
                if (!delivery.success()) { be.loadWithComponents(before, level.registryAccess()); be.setChanged(); return Outcome.failed(CookeryRefusal.STATION_CHANGED); }
            } catch (RuntimeException | LinkageError broken) {
                be.loadWithComponents(before, level.registryAccess()); be.setChanged(); throw broken;
            }
            loaded = true;
            TavernBatch.remember(level, at, new TavernBatch.Batch(colony, batch, barrel)); changed.run();
            return new Outcome.Done(List.of(), delivery.outputs(), Optional.empty());
        }
        private Outcome takeJuice(ServerLevel level, Worker who) {
            ItemStack bucket = supplied(who, carrier(), 1);
            if (bucket.isEmpty()) return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
            var tub = TavernStation.tub(level, at).orElseThrow();
            var before = tub.saveWithoutMetadata(level.registryAccess());
            KitchenDelivery.Result result;
            try {
                result = KitchenDelivery.run(level, at.block(level), who, bucket, () -> tub.getResult(who.body(), bucket));
            } catch (RuntimeException | LinkageError broken) {
                tub.loadWithComponents(before, level.registryAccess()); tub.setChanged();
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            if (!result.success()) return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            finished = true; TavernBatch.clear(level, at); changed.run();
            return new Outcome.Done(List.of(), result.outputs(), Optional.empty());
        }
    }

    /** Each commit settles one visible stage. A cancellation leaves the owned bottle in the world. */
    private final class Bottle implements Node {
        private final int stage;
        private final NodeSpec spec;
        Bottle(int stage) {
            this.stage = stage;
            var output = new Amount(Goods.specOf(TavernStation.output(true)), 1);
            spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(at), stances, Workload.Once.of(20))
                .needs(stage == 0 ? List.of(new Need(carrier(), 1, true)) : List.of())
                .gives(stage == 2 ? List.of(output) : List.of()).leaves(stage == 2 ? List.of(output) : List.of())
                .vocation(WaresContent.trade()).gesture(WorkGesture.SWING).focus(at.cell())
                .doing(stage == 2 ? CookeryContent.TAKING : CookeryContent.PREPARING).done();
        }
        public NodeSpec spec() { return spec; }
        public Optional<RefusalKind> refusal(ServerLevel level) { return TavernWork.this.refusal(level); }
        public Outcome commit(ServerLevel level, Worker who) {
            var tap = TavernStation.tap(level, at).orElse(null);
            if (stage != bottle || refusal(level).isPresent() || !bookings.first(at, TavernWork.this.id())
                    || tap == null || !reachable(level, who, stances, tap)) return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            var display = tap.below();
            var nativeBarrel = TavernStation.barrel(level, at).orElseThrow();
            var before = nativeBarrel.saveWithoutMetadata(level.registryAccess());
            var oldState = level.getBlockState(display);
            var oldEntity = level.getBlockEntity(display);
            var oldData = oldEntity == null ? null : oldEntity.saveWithoutMetadata(level.registryAccess());
            var products = new ArrayList<ItemStack>();
            try {
                if (stage == 0) {
                    ItemStack empty = supplied(who, carrier(), 1);
                    if (empty.isEmpty() || !oldState.isAir()) return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
                    var hit = new BlockHitResult(Vec3.atCenterOf(display).add(0, -0.5, 0), Direction.UP, display.below(), false);
                    if (!((BlockItem)empty.getItem()).place(new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, empty, hit)).consumesAction())
                        throw new IllegalStateException("Wine bottling carrier changed");
                } else if (stage == 1) {
                    if (!nativeBarrel.canTapExtract(level, tap, who.body())) throw new IllegalStateException("Wine tap changed");
                    nativeBarrel.doTapExtract(level, tap);
                    if (level.getBlockState(display).getBlock().asItem() != ModItems.WINE.get()
                            || !(level.getBlockEntity(display) instanceof DrinkBlockEntity)) throw new IllegalStateException("Native wine did not appear");
                } else {
                    if (!(oldEntity instanceof DrinkBlockEntity drink)) throw new IllegalStateException("Native wine disappeared");
                    var drops = net.minecraft.world.level.block.Block.getDrops(oldState, level, display, drink, who.body(), ItemStack.EMPTY);
                    if (drops.size() != 1 || !drops.getFirst().is(ModItems.WINE.get()) || drops.getFirst().getCount() != 1)
                        throw new IllegalStateException("Native wine output changed");
                    products.add(drops.getFirst());
                    if (!level.setBlockAndUpdate(display, Blocks.AIR.defaultBlockState())) throw new IllegalStateException("Bottle pickup failed");
                }
            } catch (RuntimeException | LinkageError broken) {
                nativeBarrel.loadWithComponents(before, level.registryAccess()); nativeBarrel.setChanged();
                level.setBlockAndUpdate(display, oldState);
                if (oldData != null && level.getBlockEntity(display) != null) {
                    level.getBlockEntity(display).loadWithComponents(oldData, level.registryAccess());
                    level.getBlockEntity(display).setChanged();
                }
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            bottle = (stage + 1) % 3;
            if (stage == 2) remaining--;
            finished = remaining == 0;
            if (finished) TavernBatch.clear(level, at);
            else TavernBatch.remember(level, at, new TavernBatch.Batch(colony, batch, true, remaining, bottle));
            changed.run();
            return new Outcome.Done(List.of(), products, Optional.empty());
        }
    }
}
