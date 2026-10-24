package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Staged;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** No items leave storage before the runner reaches it. Staged draw and settlement are one server transaction. */
final class PatrolSupplyNode implements Node, Staged {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways", "patrol");
    private static final ResourceLocation RESUPPLYING = ResourceLocation.fromNamespaceAndPath("folkways", "patrol_resupplying");
    private final PatrolSupplies supplies;
    private final PatrolSupplies.Authority authority;
    private final WorldPos source;
    private final PatrolSupplies.Demand demand;
    private final long deadline;
    private final NodeSpec spec;
    private Container borrowed;
    private PatrolSupplies.State failure;
    private String failureReason;

    PatrolSupplyNode(PatrolSupplies supplies, PatrolSupplies.Authority authority, WorldPos source,
            PatrolSupplies.Demand demand, int missing, Stances stances, long deadline) {
        this.supplies = supplies;
        this.authority = authority;
        this.source = source;
        this.demand = demand;
        this.deadline = deadline;
        spec = NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(source), stances, Workload.Once.of(10))
            .needs(new Need(demand.goods(), missing)).doing(RESUPPLYING, demand.goods()).focus(source.cell()).done();
    }

    PatrolSupplies.Authority authority() { return authority; }
    PatrolSupplies.Demand demand() { return demand; }
    WorldPos source() { return source; }
    long deadline() { return deadline; }
    PatrolSupplies.State failure() { return failure; }
    Optional<String> failureReason() { return Optional.ofNullable(failureReason); }
    Urge urge() {
        return new Urge(ResourceLocation.fromNamespaceAndPath("folkways", "patrol_supply/" + spec.id()),
            Urge.DIRE - .25, true, () -> this);
    }

    @Override public NodeSpec spec() { return spec; }
    @Override public boolean directTravel() { return true; }
    @Override public boolean ready(ServerLevel level) { return supplies.active(this) && level.getGameTime() < deadline; }

    private boolean withinReach(ServerLevel level) {
        Optional<BlockPos> feet = WorldSpaces.storage(level, WorldSpaces.at(authority.body()));
        return feet.isPresent() && WorldSpaces.at(authority.body()).sameRealm(source)
            && Reach.withinReach(feet.get(), source.block(level)) && Reach.inSight(level, feet.get(), source.block(level));
    }

    @Override
    public Drawn draw(ServerLevel level, Worker who) {
        if (!ready(level) || who.body() != authority.body() || who.pack() != authority.body().pack() || !withinReach(level)) {
            failure = PatrolSupplies.State.CANCELLED;
            failureReason = !ready(level) ? "supply authority expired before draw"
                : who.body() != authority.body() || who.pack() != authority.body().pack() ? "supply worker changed before draw"
                : "supply source outside actual reach or sight before draw";
            return new Short(LaborRefusal.GOODS_GONE);
        }
        Optional<Container> stock = supplies.source(level, source);
        if (stock.isEmpty()) {
            failure = PatrolSupplies.State.NO_STOCK;
            return new Short(LaborRefusal.NO_SOURCE);
        }
        int missing = (int) Math.min(spec.needs().getFirst().count(), supplies.missing(authority.body(), demand));
        List<ItemStack> incoming = supplies.preview(level, source, stock.get(), demand, missing);
        if (incoming.stream().mapToInt(ItemStack::getCount).sum() < missing) {
            failure = PatrolSupplies.State.NO_STOCK;
            return new Short(LaborRefusal.GOODS_GONE);
        }
        if (!PatrolSupplies.fits(who.pack(), incoming)) {
            failure = PatrolSupplies.State.NO_ROOM;
            return new Short(LaborRefusal.NOWHERE_TO_PUT_IT);
        }
        List<ItemStack> taken = new ArrayList<>();
        borrowed = stock.get();
        try {
            for (ItemStack wanted : incoming) {
                long before = sameCount(borrowed, wanted);
                ItemStack actual;
                try {
                    actual = Containers.extract(borrowed, stack -> ItemStack.isSameItemSameComponents(stack, wanted), wanted.getCount());
                } catch (RuntimeException | LinkageError broken) {
                    // removeItem/setChanged can throw after the physical mutation but before returning
                    // its stack. The missing component-identical units still belong to this transaction.
                    int removed = (int) Math.min(wanted.getCount(), Math.max(0, before - sameCount(borrowed, wanted)));
                    if (removed > 0) { taken.add(wanted.copyWithCount(removed)); }
                    throw broken;
                }
                if (!actual.isEmpty()) { taken.add(actual); }
                if (actual.getCount() != wanted.getCount()) {
                    restore(level, who, taken);
                    failure = PatrolSupplies.State.NO_STOCK;
                    return new Short(LaborRefusal.GOODS_GONE);
                }
            }
        } catch (RuntimeException | LinkageError broken) {
            // Core has not received Taken yet, so this node still owns every preceding successful draw.
            restore(level, who, taken);
            throw broken;
        }
        return new Taken(taken);
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        if (!ready(level) || borrowed == null || who.body() != authority.body() || who.pack() != authority.body().pack()
                || !withinReach(level) || supplies.source(level, source).isEmpty()) {
            failure = PatrolSupplies.State.CANCELLED;
            return Outcome.failed(LaborRefusal.GOODS_GONE);
        }
        if (!PatrolSupplies.fits(who.pack(), who.supplied())) {
            failure = PatrolSupplies.State.NO_ROOM;
            return Outcome.failed(LaborRefusal.NOWHERE_TO_PUT_IT);
        }
        borrowed = null; // Core now owns this one settlement; repeated lifecycle callbacks cannot refund it.
        return new Outcome.Done(List.of(), List.of(), Optional.empty(), who.supplied());
    }

    @Override
    public void restore(ServerLevel level, Worker who, List<ItemStack> goods) {
        if (borrowed == null) { return; }
        Container original = borrowed;
        borrowed = null;
        for (ItemStack stack : goods) {
            ItemStack left = restoreInto(original, stack);
            if (!left.isEmpty() && authority.body().isAlive()) { left = restoreInto(authority.body().pack(), left); }
            if (!left.isEmpty()) { authority.body().spawnAtLocation(left); }
        }
    }

    private static ItemStack restoreInto(Container into, ItemStack stack) {
        long before;
        try { before = sameCount(into, stack); }
        catch (RuntimeException | LinkageError unreadable) { return stack.copy(); }
        try { return Containers.insert(into, stack); }
        catch (RuntimeException | LinkageError broken) {
            // An adapter may fail after inserting part of the stack. Only the still-owned remainder
            // goes to the next fallback; an exception on one stack must not abandon later stacks.
            long added = Math.max(0, sameCount(into, stack) - before);
            return stack.copyWithCount((int) Math.max(0, stack.getCount() - added));
        }
    }

    private static long sameCount(Container into, ItemStack sought) {
        long count = 0;
        for (int slot = 0; slot < into.getContainerSize(); slot++) {
            ItemStack stack = into.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, sought)) { count += stack.getCount(); }
        }
        return count;
    }

    @Override public void released(ServerLevel level, Worker who, Ending how) { supplies.finished(this, how); }
}
