package io.github.izakyl.folkways.core.engine.plan.haul;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Doing;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.core.engine.plan.Manifest;
import io.github.izakyl.folkways.core.engine.plan.Staged;
import io.github.izakyl.folkways.core.engine.plan.Stores;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

public final class TransferNode implements Node, Staged {
    public sealed interface Endpoint {
        record Store(Stash stash) implements Endpoint { }
        record Pack(Optional<UUID> resident) implements Endpoint { }
        static Endpoint store(Stash stash) { return new Store(stash); }
        static Endpoint pack() { return new Pack(Optional.empty()); }
        static Endpoint pack(UUID resident) { return new Pack(Optional.of(resident)); }
    }

    private final NodeSpec spec;
    private final Endpoint from;
    private final Endpoint to;
    private final Manifest cargo;
    private final boolean handed;
    private final LongConsumer landed;

    public TransferNode(UUID id, Endpoint from, Endpoint to, Manifest goods, Stances stances,
                        WorkSite site) {
        this(NodeSpec.of(id, Haul.DOMAIN, site, stances,
            Workload.Once.of(FolkwaysConfig.haulReachTicks(), Haul::haste))
            .vocation(Haul.trade()).gesture(WorkGesture.SWING)
            .doing(from instanceof Endpoint.Store ? Doings.FETCHING : Doings.STOWING, about(goods))
            .done(), from, to, goods, false, count -> { });
        if ((from instanceof Endpoint.Store) == (to instanceof Endpoint.Store) || goods.isEmpty()) {
            throw new IllegalArgumentException("a transfer needs a store, a pack, and positive amounts");
        }
    }

    // A transfer worked at its store from one of `stands`; none when there is nowhere to stand or nothing to move.
    public static Optional<TransferNode> at(Endpoint from, Endpoint to, Manifest goods, Set<Stand> stands) {
        Stash store = from instanceof Endpoint.Store(Stash stash) ? stash
            : to instanceof Endpoint.Store(Stash stash) ? stash : null;
        if (store == null || goods.isEmpty() || stands.isEmpty()) {
            return Optional.empty();
        }
        return Stances.of(stands.stream().map(Stand::cell).toList()).map(stances ->
            new TransferNode(UUID.randomUUID(), from, to, goods, stances, new WorkSite.AtBlock(store.pos())));
    }

    // A delivery into a store: a put that needs the goods it puts into `into`, fed like any other need. What it puts
    // down is whatever the steps feeding it hand on, known only as the plan grows them. Done, it tells `landed` how
    // many it put down.
    static TransferNode delivering(UUID id, Stash into, Need goods, Stances stances, LongConsumer landed) {
        NodeSpec spec = NodeSpec.of(id, Haul.DOMAIN, new WorkSite.AtBlock(into.pos()), stances,
            Workload.Once.of(FolkwaysConfig.haulReachTicks(), Haul::haste))
            .needs(goods).vocation(Haul.trade()).gesture(WorkGesture.SWING).doing(Doings.STOWING, goods.spec())
            .done();
        return new TransferNode(spec, Endpoint.pack(), Endpoint.store(into), new Manifest(List.of(), List.of()), true,
            landed);
    }

    private TransferNode(NodeSpec spec, Endpoint from, Endpoint to, Manifest cargo, boolean handed,
                         LongConsumer landed) {
        this.cargo = cargo;
        this.spec = spec;
        this.from = from;
        this.to = to;
        this.handed = handed;
        this.landed = landed;
    }

    private static Optional<String> about(Manifest goods) {
        return goods.exact().stream().findFirst()
            .map(stack -> stack.getItem().getDescriptionId())
            .or(() -> goods.pending().stream().findFirst().flatMap(need -> Doings.about(need.spec())));
    }

    public TransferNode resolved(Manifest manifest) {
        return new TransferNode(spec, from, to, manifest, false, landed);
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        if (how instanceof Ending.Done) {
            landed.accept(amount());
        }
    }

    public List<ItemStack> goods() {
        return cargo.exact();
    }

    // How many goods it moves, as far as the plan knows them yet.
    public long amount() {
        long count = 0;
        for (ItemStack stack : cargo.exact()) {
            count += stack.getCount();
        }
        for (Need need : cargo.pending()) {
            count += need.count();
        }
        return count;
    }

    // What is carried is known only once the plan has bound it, and then it is the manifest, not the spec.
    @Override
    public Doing.Wares wares() {
        if (handed) {
            return spec.wares();
        }
        List<Doing.Ware> carried = new ArrayList<>();
        for (ItemStack stack : cargo.exact()) {
            carried.add(new Doing.Ware(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount()));
        }
        for (Need need : cargo.pending()) {
            Doings.item(need.spec()).ifPresent(item -> carried.add(new Doing.Ware(item, need.count())));
        }
        return new Doing.Wares(carried, List.of());
    }

    // Whether what it puts down is only known from what is handed on to it.
    public boolean handedOn() { return handed; }

    // The store a delivery puts its goods into, for a put that is one.
    public Optional<Stash> delivers() {
        return handed && to instanceof Endpoint.Store(Stash stash) ? Optional.of(stash) : Optional.empty();
    }
    @Override public NodeSpec spec() { return spec; }
    @Override public boolean replannable() { return true; }

    // Taking from, or putting into, the same store as the transfer just done is one reach.
    @Override
    public boolean joins(Node done) {
        return done instanceof TransferNode other
            && (from instanceof Endpoint.Store) == (other.from instanceof Endpoint.Store)
            && store().equals(other.store());
    }

    private Endpoint store() {
        return from instanceof Endpoint.Store ? from : to;
    }

    @Override
    public Optional<UUID> worker() {
        if (from instanceof Endpoint.Pack pack && pack.resident().isPresent()) {
            return pack.resident();
        }
        return to instanceof Endpoint.Pack pack ? pack.resident() : Optional.empty();
    }

    @Override
    public boolean acceptsInput(Stash stash) {
        return !(to instanceof Endpoint.Store store) || !store.stash().equals(stash);
    }

    private Optional<Container> inventory(Endpoint endpoint, ServerLevel level, Worker who) {
        return switch (endpoint) {
            case Endpoint.Store store -> Stores.at(level, store.stash().pos());
            case Endpoint.Pack pack -> pack.resident().isEmpty()
                || pack.resident().get().equals(who.resident().id())
                ? Optional.of(who.pack()) : Optional.empty();
        };
    }

    // The core lifts the planned goods out of the source; all of them or none.
    @Override
    public Drawn draw(ServerLevel level, Worker who) {
        List<ItemStack> goods = cargo.exact();
        List<Need> produced = cargo.pending();
        if (goods.isEmpty() && produced.isEmpty()) {
            return new Short(HaulRefusal.NOTHING_CARRIED);
        }
        Container source = inventory(from, level, who).orElse(null);
        Container destination = inventory(to, level, who).orElse(null);
        if (source == null || destination == null || source == destination) {
            return new Short(HaulRefusal.NOT_A_CONTAINER);
        }
        SimpleContainer taken = snapshot(source);
        List<ItemStack> moved = new ArrayList<>();
        for (ItemStack exact : goods) {
            ItemStack lifted = Containers.extract(taken,
                stack -> ItemStack.isSameItemSameComponents(stack, exact), exact.getCount());
            if (lifted.getCount() != exact.getCount()) {
                return new Short(HaulRefusal.NOTHING_TO_TAKE);
            }
            moved.add(lifted);
        }
        for (Need expected : produced) {
            long left = expected.count();
            while (left > 0) {
                ItemStack lifted = Containers.extract(taken,
                    stack -> Goods.matches(stack, expected.spec()), (int) Math.min(left, Integer.MAX_VALUE));
                if (lifted.isEmpty()) {
                    return new Short(HaulRefusal.NOTHING_TO_TAKE);
                }
                left -= lifted.getCount();
                moved.add(lifted);
            }
        }
        copy(taken, source);
        return new Taken(moved);
    }

    @Override
    public void restore(ServerLevel level, Worker who, List<ItemStack> goods) {
        Container source = inventory(from, level, who).orElse(who.pack());
        for (ItemStack stack : goods) {
            ItemStack left = Containers.insert(source, stack);
            if (!left.isEmpty() && source != who.pack()) {
                left = Containers.insert(who.pack(), left);
            }
            if (!left.isEmpty()) {
                who.spill(left);
            }
        }
    }

    // Putting the goods down is the whole of the work, and it happens only if they all fit.
    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        Container destination = inventory(to, level, who).orElse(null);
        if (destination == null) {
            return Outcome.failed(HaulRefusal.NOT_A_CONTAINER);
        }
        List<ItemStack> goods = who.supplied();
        SimpleContainer placed = snapshot(destination);
        for (ItemStack stack : goods) {
            if (!Containers.insert(placed, stack).isEmpty()) {
                return Outcome.failed(HaulRefusal.NO_ROOM);
            }
        }
        copy(placed, destination);
        cargo.bind(goods);
        return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(Haul.trade(), 1)));
    }

    private static SimpleContainer snapshot(Container container) {
        SimpleContainer copy = new SimpleContainer(container.getContainerSize()) {
            @Override public boolean canPlaceItem(int slot, ItemStack stack) {
                return Containers.accepts(container, slot, stack);
            }
            @Override public int getMaxStackSize() { return container.getMaxStackSize(); }
        };
        copy(container, copy);
        return copy;
    }

    private static void copy(Container from, Container to) {
        for (int slot = 0; slot < from.getContainerSize(); slot++) {
            if (!ItemStack.matches(from.getItem(slot), to.getItem(slot))) {
                to.setItem(slot, from.getItem(slot).copy());
            }
        }
        to.setChanged();
    }
}
