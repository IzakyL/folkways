package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.FolkwaysConfig;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort.Operation;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.inventory.api.port.TransferResult;
import io.github.izakyl.folkways.core.inventory.internal.Stores;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Manifest;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Recourse;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkBatch;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.api.Xp;
import io.github.izakyl.folkways.core.work.support.Staged;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

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
    private boolean directed;
    // An order's own delivery: its put passes the destination's incoming goods filters. A relay that only stows what
    // a resident carries is handed on too, but is not ordered and keeps to the filters.
    private boolean ordered;
    private final LongConsumer landed;
    private Optional<Delivery.Source> collection = Optional.empty();
    private boolean collecting;
    private BiFunction<ServerLevel, Operation, Optional<StorePort>> portResolver;
    private Optional<UUID> boundWorker = Optional.empty();

    public TransferNode(UUID id, Endpoint from, Endpoint to, Manifest goods, Stances stances,
                        WorkSite site) {
        this(NodeSpec.of(id, HaulWork.DOMAIN, site, stances,
            Workload.Once.of(FolkwaysConfig.haulReachTicks(), HaulWork::haste))
            .vocation(HaulWork.trade()).gesture(WorkGesture.SWING)
            .doing(from instanceof Endpoint.Store ? Doings.FETCHING : Doings.STOWING, about(goods))
            .done(), from, to, goods, false, count -> { });
        if ((from instanceof Endpoint.Store) == (to instanceof Endpoint.Store) || goods.isEmpty()) {
            throw new IllegalArgumentException("a transfer needs a store, a pack, and positive amounts");
        }
    }

    /** A private store obligation, using the usual atomic draw/restore and pack handling. */
    public static TransferNode obligation(NodeSpec spec, Stash store, List<ItemStack> goods, boolean take,
            UUID worker, BiFunction<ServerLevel, Operation, Optional<StorePort>> resolver) {
        TransferNode node = new TransferNode(spec,
            take ? Endpoint.store(store) : Endpoint.pack(worker),
            take ? Endpoint.pack(worker) : Endpoint.store(store),
            new Manifest(goods, List.of()), false, count -> { });
        node.boundWorker = Optional.of(worker);
        node.portResolver = resolver;
        return node;
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

    public static TransferNode relay(Stash into, Need goods, Set<Stand> stands) {
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), HaulWork.DOMAIN, new WorkSite.AtBlock(into.pos()),
                new Stances.Cells(stands.stream().map(Stand::cell).collect(Collectors.toSet())),
                Workload.Once.of(FolkwaysConfig.haulReachTicks(), HaulWork::haste))
            .needs(new Need(goods.spec(), goods.count(), true)).gives(new Amount(goods.spec(), goods.count()))
            .vocation(HaulWork.trade()).done();
        return new TransferNode(spec, Endpoint.pack(), Endpoint.store(into),
            new Manifest(List.of(), List.of()), true, count -> { });
    }

    // Transit keeps ordinary store permissions even though it is part of a directed delivery.
    public static TransferNode transitPut(Stash into, Need goods, Set<Stand> stands) {
        TransferNode put = relay(into, goods, stands);
        put.directed = false;
        return put;
    }

    public static TransferNode transitTake(Stash from, Need goods, Set<Stand> stands) {
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), HaulWork.DOMAIN, new WorkSite.AtBlock(from.pos()),
                new Stances.Cells(stands.stream().map(Stand::cell).collect(Collectors.toSet())),
                Workload.Once.of(FolkwaysConfig.haulReachTicks(), HaulWork::haste))
            .gives(new Amount(goods.spec(), goods.count())).vocation(HaulWork.trade()).done();
        return new TransferNode(spec, Endpoint.store(from), Endpoint.pack(),
            new Manifest(List.of(), List.of(goods)), false, count -> { });
    }

    // A delivery into a store: a put that needs the goods it puts into `into`, fed like any other need. What it puts
    // down is whatever the steps feeding it hand on, known only as the plan grows them. Done, it tells `landed` how
    // many it put down.
    public static TransferNode delivering(UUID id, Stash into, Need goods, Stances stances, LongConsumer landed) {
        NodeSpec spec = NodeSpec.of(id, HaulWork.DOMAIN, new WorkSite.AtBlock(into.pos()), stances,
            Workload.Once.of(FolkwaysConfig.haulReachTicks(), HaulWork::haste))
            .needs(goods).vocation(HaulWork.trade()).gesture(WorkGesture.SWING).doing(Doings.STOWING, goods.spec())
            .done();
        TransferNode delivery = new TransferNode(spec, Endpoint.pack(), Endpoint.store(into),
            new Manifest(List.of(), List.of()), true, landed);
        delivery.ordered = true;
        return delivery;
    }

    // A directed collection uses the ordinary take and put, kept together until the exact stack is delivered.
    public static Grown collecting(Delivery delivery) {
        Delivery.Source source = delivery.source().orElseThrow();
        Manifest cargo = new Manifest(List.of(source.stack()), List.of());
        Amount load = new Amount(Goods.specOf(source.stack()), source.stack().getCount());
        NodeSpec takeSpec = NodeSpec.of(UUID.randomUUID(), HaulWork.DOMAIN, new WorkSite.AtBlock(source.at()),
                source.stances(), Workload.Once.of(FolkwaysConfig.haulReachTicks(), HaulWork::haste))
            .leaves(List.of(load)).vocation(HaulWork.trade()).gesture(WorkGesture.SWING)
            .doing(Doings.FETCHING, load.spec()).done();
        TransferNode take = new TransferNode(takeSpec, Endpoint.store(new Stash(source.at())), Endpoint.pack(),
            cargo, false, count -> { });
        take.collection = Optional.of(source);
        take.collecting = true;
        NodeSpec putSpec = NodeSpec.of(delivery.id(), HaulWork.DOMAIN, delivery.spec().site(), delivery.spec().stances(),
                Workload.Once.of(FolkwaysConfig.haulReachTicks(), HaulWork::haste))
            .leaves(List.of(new Amount(load.spec(), -load.count()))).vocation(HaulWork.trade()).gesture(WorkGesture.SWING)
            .doing(Doings.STOWING, load.spec()).done();
        TransferNode put = new TransferNode(putSpec, Endpoint.pack(),
            Endpoint.store(new Stash(delivery.spec().site().where())), cargo, false, delivery.arrived());
        put.collecting = true;
        return Grown.then(take, put).byOneWorker();
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        if (portResolver != null) {
            return Recourse.waitFor(40);
        }
        return collecting && (why == HaulRefusal.NO_ROOM || !fetching() && why == HaulRefusal.NOT_A_CONTAINER)
            ? Recourse.waitFor(100) : Recourse.FAIL;
    }

    private TransferNode(NodeSpec spec, Endpoint from, Endpoint to, Manifest cargo, boolean handed,
                         LongConsumer landed) {
        this.cargo = cargo;
        this.spec = spec;
        this.from = from;
        this.to = to;
        this.handed = handed;
        this.directed = handed;
        this.landed = landed;
    }

    private static Optional<String> about(Manifest goods) {
        return goods.exact().stream().findFirst()
            .map(stack -> stack.getItem().getDescriptionId())
            .or(() -> goods.pending().stream().findFirst().flatMap(need -> Doings.about(need.spec())));
    }

    public TransferNode resolved(Manifest manifest) {
        TransferNode resolved = new TransferNode(spec, from, to, manifest, false, landed);
        resolved.directed = directed;
        resolved.ordered = ordered;
        resolved.portResolver = portResolver;
        resolved.boundWorker = boundWorker;
        return resolved;
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

    // Whether it is an order's own delivery, which passes the destination's incoming goods filters.
    public boolean ordered() { return ordered; }

    // Whether what it puts down is only known from what is handed on to it.
    public boolean handedOn() { return handed; }

    // The store a delivery puts its goods into, for a put that is one.
    public Optional<Stash> delivers() {
        return handed && to instanceof Endpoint.Store(Stash stash) ? Optional.of(stash) : Optional.empty();
    }

    // The manifest can also be bound after planning, when a preceding transfer commits its exact stacks.
    @Override
    public NodeSpec spec() {
        Optional<String> about = about(cargo).or(spec::about);
        return about.equals(spec.about()) ? spec : spec.about(about);
    }

    @Override public boolean replannable() { return true; }

    // Taking from, or putting into, the same store as the transfer just done is one reach.
    @Override
    public boolean joins(Node done) {
        return done instanceof TransferNode other
            && (from instanceof Endpoint.Store) == (other.from instanceof Endpoint.Store)
            && store().equals(other.store());
    }

    @Override
    public Optional<WorkBatch> batch(Body body) {
        return fetching() ? Optional.of(new WorkBatch(HaulWork.DOMAIN, (int) body.effects().value(HaulWork.BATCH_SIZE)))
            : Optional.empty();
    }

    public boolean fetching() {
        return from instanceof Endpoint.Store;
    }

    private Endpoint store() {
        return from instanceof Endpoint.Store ? from : to;
    }

    @Override
    public Optional<UUID> worker() {
        if (boundWorker.isPresent()) {
            return boundWorker;
        }
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
            case Endpoint.Store store -> Stores.at(level, store.stash().pos(), ordered && endpoint == to);
            case Endpoint.Pack pack -> pack.resident().isEmpty()
                || pack.resident().get().equals(who.resident().id())
                ? Optional.of(who.pack()) : Optional.empty();
        };
    }

    // The core lifts the planned goods out of the source; all of them or none.
    @Override
    public Drawn draw(ServerLevel level, Worker who) {
        if (from instanceof Endpoint.Store store) {
            InventoryFeedback.store(level, store.stash().pos());
        }
        if (collection.isPresent()) {
            Delivery.Source source = collection.get();
            if (!source.at().in(level) || !level.isLoaded(source.at().block(level))
                || level.getBlockState(source.at().block(level)) != source.state()
                || level.getBlockEntity(source.at().block(level)) != source.entity()) {
                return new Short(HaulRefusal.NOTHING_TO_TAKE);
            }
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, source.at().block(level),
                source.side().orElse(null));
            if (handler == null || source.slot() >= handler.getSlots()) {
                return new Short(HaulRefusal.NOT_A_CONTAINER);
            }
            ItemStack wanted = source.stack();
            ItemStack can = handler.extractItem(source.slot(), wanted.getCount(), true);
            if (!ItemStack.matches(can, wanted)) {
                return new Short(HaulRefusal.NOTHING_TO_TAKE);
            }
            if (!Containers.insert(snapshot(who.pack()), wanted).isEmpty()) {
                return new Short(HaulRefusal.NO_ROOM);
            }
            ItemStack taken = handler.extractItem(source.slot(), wanted.getCount(), false);
            InventoryFeedback.store(level, source.at());
            return taken.isEmpty() ? new Short(HaulRefusal.NOTHING_TO_TAKE) : new Taken(List.of(taken));
        }
        if (from instanceof Endpoint.Store store) {
            if (portResolver == null && !Stores.usable(level, store.stash().pos())) {
                return new Short(HaulRefusal.UNUSABLE);
            }
            StorePort port = port(level, store.stash(), who, Operation.TAKE).orElse(null);
            if (port == null) {
                return new Short(HaulRefusal.NOT_A_CONTAINER);
            }
            if (!port.usability().usable()) {
                return new Short(HaulRefusal.UNUSABLE);
            }
            List<Pull> pulls = new ArrayList<>();
            cargo.exact().forEach(stack -> pulls.add(new Pull.Exact(stack)));
            cargo.pending().forEach(need -> pulls.add(new Pull.Matching(need.spec(), need.count())));
            if (pulls.isEmpty()) {
                return new Short(HaulRefusal.NOTHING_CARRIED);
            }
            List<StoreRule> rules = Stores.rulesAt(level, store.stash().pos());
            TransferResult simulated = port.take(level, pulls, rules, true);
            if (simulated instanceof TransferResult.Refused refused) {
                return new Short(refusal(refused.why()));
            }
            Container pack = inventory(to, level, who).orElse(null);
            if (pack == null) {
                return new Short(HaulRefusal.NOT_A_CONTAINER);
            }
            SimpleContainer carried = snapshot(pack);
            for (ItemStack stack : ((TransferResult.Done) simulated).moved()) {
                if (!Containers.insert(carried, stack).isEmpty()) {
                    return new Short(HaulRefusal.NO_ROOM);
                }
            }
            TransferResult result = port.take(level, pulls, rules, false);
            return result instanceof TransferResult.Done done ? new Taken(done.moved())
                : new Short(refusal(((TransferResult.Refused) result).why()));
        }
        List<ItemStack> goods = cargo.exact();
        List<Need> produced = cargo.pending();
        if (goods.isEmpty() && produced.isEmpty()) {
            return new Short(HaulRefusal.NOTHING_CARRIED);
        }
        Container source = inventory(from, level, who).orElse(null);
        if (source == null) {
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
        if (collection.isEmpty() && from instanceof Endpoint.Store store) {
            StorePort port = port(level, store.stash(), who, Operation.PUT).orElse(null);
            List<StoreRule> rules = Stores.rulesAt(level, store.stash().pos());
            if (port != null && port.put(level, goods, rules, true, true).done()
                && port.put(level, goods, rules, true, false).done()) {
                return;
            }
        }
        // A collected stack already has room in the pack, including when its source cannot accept returns.
        Container source = from instanceof Endpoint.Store ? who.pack() : inventory(from, level, who).orElse(who.pack());
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
        if (to instanceof Endpoint.Store store) {
            InventoryFeedback.store(level, store.stash().pos());
        }
        if (to instanceof Endpoint.Store store) {
            if (portResolver == null && !Stores.usable(level, store.stash().pos())) {
                return Outcome.failed(HaulRefusal.UNUSABLE);
            }
            StorePort port = port(level, store.stash(), who, Operation.PUT).orElse(null);
            if (port == null) {
                return Outcome.failed(HaulRefusal.NOT_A_CONTAINER);
            }
            if (!port.usability().usable()) {
                return Outcome.failed(HaulRefusal.UNUSABLE);
            }
            List<ItemStack> goods = who.supplied();
            List<StoreRule> rules = Stores.rulesAt(level, store.stash().pos());
            if (directed && !ordered) {
                rules = rules.stream().filter(rule -> !(rule instanceof StoreRule.Access)).toList();
            }
            TransferResult simulated = port.put(level, goods, rules, ordered, true);
            if (simulated instanceof TransferResult.Refused refused) {
                return Outcome.failed(refusal(refused.why()));
            }
            TransferResult result = port.put(level, goods, rules, ordered, false);
            if (result instanceof TransferResult.Refused refused) {
                return Outcome.failed(refusal(refused.why()));
            }
            cargo.bind(goods);
            return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(HaulWork.trade(), 1)));
        }
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
        return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(HaulWork.trade(), 1)));
    }

    private static HaulRefusal refusal(TransferResult.Why why) {
        return switch (why) {
            case NO_ROOM -> HaulRefusal.NO_ROOM;
            case NOTHING_TO_TAKE -> HaulRefusal.NOTHING_TO_TAKE;
            case NOT_ALLOWED -> HaulRefusal.NOT_ALLOWED;
            case UNUSABLE -> HaulRefusal.UNUSABLE;
            case UNAVAILABLE -> HaulRefusal.NOT_A_CONTAINER;
        };
    }

    private Optional<StorePort> port(ServerLevel level, Stash stash, Worker who, Operation operation) {
        if (portResolver != null) {
            return portResolver.apply(level, operation);
        }
        Set<WorldPos> aliases = new LinkedHashSet<>();
        for (var colony : Colonies.all(level.getServer())) {
            var observed = colony.inventory().snapshot().store(stash.pos());
            if (observed.isPresent()) {
                if (!observed.get().usability().usable()
                    || observed.get().aliases().stream().anyMatch(at -> StorePorts.condemned(at).isPresent())) {
                    return Optional.empty();
                }
                aliases.addAll(observed.get().operations().keySet());
            }
        }
        aliases.add(stash.pos());
        return aliases.stream().filter(at -> at.in(level))
            .filter(at -> aliases.size() == 1 || Reach.withinReach(who.body().blockPosition(), at.block(level))
                && Reach.inSight(level, who.body().blockPosition(), at.block(level)))
            .sorted(Comparator.comparingDouble(at -> at.block(level).distSqr(who.body().blockPosition())))
            .map(at -> StorePorts.at(level, at.block(level)))
            .flatMap(Optional::stream)
            .filter(port -> port.usability().usable() && port.operations().contains(operation))
            .map(port -> port.proxyMembers().isEmpty() ? Optional.of(port)
                : port.proxyMembers().orElseThrow().contains(stash.pos())
                    ? StorePorts.at(level, stash.pos().block(level)) : Optional.<StorePort>empty())
            .flatMap(Optional::stream)
            .filter(port -> port.usability().usable() && port.operations().contains(operation)).findFirst();
    }

    static SimpleContainer snapshot(Container container) {
        return new Snapshot(container);
    }

    private static final class Snapshot extends SimpleContainer
            implements io.github.izakyl.folkways.core.item.api.Inserting {
        private final Container source;

        Snapshot(Container source) {
            super(source.getContainerSize());
            this.source = source;
            copy(source, this);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            // A snapshot must preserve even an existing stack above its current insertion limit.
            getItems().set(slot, stack);
            setChanged();
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return Containers.accepts(source, slot, stack);
        }

        private int limit(int slot, ItemStack stack) {
            return source instanceof io.github.izakyl.folkways.core.resident.api.Pack pack
                ? pack.slotLimit(slot, stack) : source.getMaxStackSize(stack);
        }

        @Override
        public ItemStack insert(ItemStack stack) {
            ItemStack left = stack.copy();
            for (int pass = 0; pass < 2 && !left.isEmpty(); pass++) {
                for (int slot = 0; slot < getContainerSize() && !left.isEmpty(); slot++) {
                    ItemStack there = getItem(slot);
                    if (there.isEmpty() != (pass == 1) || !canPlaceItem(slot, left)
                        || !there.isEmpty() && !ItemStack.isSameItemSameComponents(there, left)) {
                        continue;
                    }
                    int moved = Math.min(left.getCount(), Math.max(0, limit(slot, left) - there.getCount()));
                    if (moved > 0) {
                        setItem(slot, left.copyWithCount(there.getCount() + moved));
                        left.shrink(moved);
                    }
                }
            }
            return left;
        }
    }

    private static void copy(Container from, Container to) {
        // Backpack roots occupy base cells before their native cells. Commit native cells first so removing
        // a root cannot shrink or reorder the destination while its remaining cells are still being copied.
        for (int slot = from.getContainerSize() - 1; slot >= 0; slot--) {
            if (!ItemStack.matches(from.getItem(slot), to.getItem(slot))) {
                to.setItem(slot, from.getItem(slot).copy());
            }
        }
        to.setChanged();
    }
}
