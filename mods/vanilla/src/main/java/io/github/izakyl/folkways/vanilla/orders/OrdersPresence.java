package io.github.izakyl.folkways.vanilla.orders;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.card.api.notice.Attempt;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.card.api.notice.Notice;
import io.github.izakyl.folkways.core.card.api.notice.Sentence;
import io.github.izakyl.folkways.core.colony.api.Asking;
import io.github.izakyl.folkways.core.colony.api.Cadence;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.api.HoldingKind;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Settings;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyStalls;
import io.github.izakyl.folkways.core.front.engine.colony.SchemaCards;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.item.api.BlockNames;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Stall;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

final class OrdersPresence implements HoldingKind, ColonyPanel {

    static final int MAX_ORDERS = 256;

    private static final int SWEEP_TICKS = 20;

    private static final String TAG_ORDERS = "orders";
    private static final String TAG_SHELVES = "shelves";

    private static final String TAKE = "orders_take";
    static final String STORE = "orders_store";
    static final String FILTER_MODE = "orders_filter_mode";
    static final String HERE = "orders_here";
    private static final String FILTER_EXCEPT = "folkways_core:filter/except";
    private static final String FILTER_ONLY = "folkways_core:filter/only";
    private static final String WITHDRAW = "withdraw";

    private static final List<Card.Element> ELEMENTS = elementsForStore();

    private final Colony colony;

    private final Asking asking;
    private CompoundTag saved;
    private MinecraftServer observedServer;
    private final Map<Order.Key, Order> orders = new LinkedHashMap<>();
    private final Map<WorldPos, Shelf> shelves = new LinkedHashMap<>();
    private Set<ResourceKey<Level>> ruledIn = Set.of();

    private Map<ResourceKey<Level>, List<Grown>> stated = Map.of();

    private static final class Filling {

        private final UUID id = UUID.randomUUID();
        private final WorkSite into;
        private final Stances stances;
        private final ItemSpec what;
        private final AtomicLong remaining;
        private final long asked;
        private final Runnable delivered;

        Filling(WorkSite into, Stances stances, ItemSpec what, long count, Runnable delivered) {
            this.into = into;
            this.stances = stances;
            this.what = what;
            this.remaining = new AtomicLong(count);
            this.asked = count;
            this.delivered = delivered;
        }

        boolean begun() {
            return remaining.get() < asked;
        }

        long remaining() {
            return remaining.get();
        }

        boolean complete() {
            return remaining.get() <= 0;
        }

        Grown goal() {
            return Grown.of(Delivery.to(id, OrdersContent.ID, into, stances, what, remaining.get(),
                count -> {
                    remaining.accumulateAndGet(count, (left, arrived) -> Math.max(0, left - arrived));
                    delivered.run();
                }));
        }
    }

    private final Map<Order.Key, Filling> filling = new LinkedHashMap<>();

    private final Map<Order.Key, Long> pending = new LinkedHashMap<>();

    // The last refusal a delivery met, told on a request until goods arrive or the order goes.
    private final Map<Order.Key, RefusalKind> failed = new LinkedHashMap<>();

    OrdersPresence(Colony colony) {
        this.colony = colony;
        this.sweeping = Cadence.claim(colony, SWEEP_TICKS);
        this.asking = new Asking(colony, OrdersContent.ID, (node, how) -> {
            if (how instanceof Ending.Failed failure) {
                filling.forEach((key, delivery) -> {
                    if (delivery.id.equals(node)) {
                        failed.put(key, failure.why());
                    }
                });
            }
            refreshPending.set(true);
        });
        saved = colony.kept(OrdersContent.ID);
        Reader kept = Reader.of(saved);
        if (kept.keys().isEmpty()) {
            return;
        }
        for (Reader saved : kept.requiredChildren(TAG_ORDERS)) {
            Order.load(saved).ifPresent(order -> {
                orders.put(order.key(), order);
                long left = saved.longValue("remaining").orElseThrow();
                if (left > 0) {
                    pending.put(order.key(), left);
                }
            });
        }
        for (Reader saved : kept.requiredChildren(TAG_SHELVES)) {
            Shelf.load(saved).ifPresent(shelf -> shelves.put(shelf.into(), shelf));
        }
    }

    Map<UUID, Long> commitments(ItemSpec goods) {
        Map<UUID, Long> found = new LinkedHashMap<>();
        filling.forEach((key, delivery) -> {
            if (!delivery.complete() && Goods.overlap(goods, delivery.what)) {
                found.put(delivery.id, delivery.remaining());
            }
        });
        return Map.copyOf(found);
    }

    private void write() {
        settleCompleted();
        failed.keySet().retainAll(orders.keySet());
        CompoundTag next = Writer.of().children(TAG_ORDERS, orders.values(), order -> {
            Filling delivery = filling.get(order.key());
            long left = delivery == null ? pending.getOrDefault(order.key(), 0L) : delivery.remaining();
            return Writer.of(order.save()).longValue("remaining", left).tag();
        }).children(TAG_SHELVES, shelves.values(), Shelf::save).tag();
        if (!next.equals(saved)) {
            colony.keep(OrdersContent.ID, next);
            saved = next;
        }
    }

    private static List<Card.Element> elementsForStore() {
        List<Card.Element> elements = new ArrayList<>();
        elements.add(new Card.Element.Heading(label("folkways.panel.orders.shelf"), Card.Layout.ROW));
        elements.add(new Card.Element.Flag(TAKE, Optional.of(icon("hopper")),
            Optional.of(label("folkways.panel.orders.access.take")), true));
        elements.add(new Card.Element.Flag(STORE, Optional.of(icon("chest")),
            Optional.of(label("folkways.panel.orders.access.store")), true));
        elements.add(new Card.Element.Heading(label("folkways.panel.orders.filter.section"), Card.Layout.ROW));
        elements.add(new Card.Element.Choice(FILTER_MODE, List.of(FILTER_EXCEPT, FILTER_ONLY), List.of(), List.of(),
            Optional.of(icon("iron_bars")), Optional.of(label("folkways.panel.filter.mode")), Optional.empty(),
            Optional.empty(), Map.of()));
        elements.addAll(SchemaCards.elements(new Schema(List.of(OrdersContent.FILTER))));
        elements.add(new Card.Element.Heading(label("folkways.panel.orders.replenish"), Card.Layout.ROW));
        elements.addAll(SchemaCards.elements(new Schema(List.of(OrdersContent.ITEM, OrdersContent.LOW,
            OrdersContent.COUNT))));
        elements.add(new Card.Element.Button(OrderAct.MAINTAIN, Optional.of(icon("repeater")), Map.of(),
            Optional.of(label("folkways.panel.orders.maintain"))));
        elements.add(new Card.Element.Button(OrderAct.DELIVER, Optional.of(icon("chest_minecart")), Map.of(),
            Optional.of(label("folkways.panel.orders.deliver"))));
        elements.add(new Card.Element.Heading(label("folkways.panel.orders.here")));
        elements.add(new Card.Element.Rows(HERE, Optional.of(label("folkways.panel.orders.here.empty"))));
        return List.copyOf(elements);
    }

    @Override
    public List<Card.Element> elements() {
        return ELEMENTS;
    }

    @Override
    public Optional<List<DefinedFacilities.Problem>> act(Held held, ServerPlayer by, String routine,
            Map<String, Value> arguments) {
        return act(held, by.serverLevel().getServer(), routine, arguments);
    }

    Optional<List<DefinedFacilities.Problem>> act(Held held, MinecraftServer server, String routine,
            Map<String, Value> arguments) {
        if (!List.of(OrderAct.MAINTAIN, OrderAct.DELIVER, WITHDRAW).contains(routine)) {
            return Optional.empty();
        }
        Optional<ServerLevel> loaded = held.site().level(server).filter(level -> isContainer(level, held.site()));
        if (loaded.isEmpty()) {
            return problems(routine, Attempt.refused(OrderRefusal.NOT_A_CONTAINER));
        }
        ServerLevel level = loaded.get();
        WorldPos into = anchor(held, level);
        Attempt attempt = switch (routine) {
            case OrderAct.MAINTAIN -> file(into, true, colony.view(level));
            case OrderAct.DELIVER -> file(into, false, colony.view(level));
            default -> arguments.get("goods") instanceof Value.Text goods
                ? withdraw(into, goods.value()) : Attempt.refused(OrderRefusal.UNKNOWN_ITEM);
        };
        return problems(routine, attempt);
    }

    private static Optional<List<DefinedFacilities.Problem>> problems(String path, Attempt attempt) {
        return Optional.of(attempt.refusal().map(why ->
            List.of(new DefinedFacilities.Problem(path, why))).orElseGet(List::of));
    }

    private static boolean isContainer(ServerLevel level, WorldPos at) {
        return WorldSpaces.storage(level, at).filter(level::isLoaded)
            .flatMap(cell -> Containers.at(level, Containers.anchor(level, cell))).isPresent();
    }

    private static WorldPos anchor(Held held, ServerLevel level) {
        return WorldPos.of(level, Containers.anchor(level, held.site().block(level)));
    }

    @Override
    public Optional<Value> parameter(Held held, String key) {
        var server = ServerLifecycleHooks.getCurrentServer();
        Shelf shelf = shelfAt(Optional.ofNullable(server).flatMap(held.site()::level)
            .filter(level -> WorldSpaces.storage(level, held.site()).filter(level::isLoaded).isPresent())
            .map(level -> anchor(held, level)).orElse(held.site()));
        Shelf.Admitting filter = shelf.admitting().orElseGet(() -> new Shelf.Admitting(false, List.of()));
        return switch (key) {
            case TAKE -> Optional.of(Value.of(shelf.take()));
            case STORE -> Optional.of(Value.of(shelf.store()));
            case FILTER_MODE -> Optional.of(new Value.Text(filter.only() ? FILTER_ONLY : FILTER_EXCEPT));
            default -> key.equals(OrdersContent.FILTER.key()) ? Optional.of(new Value.Items(filter.filters()))
                : Optional.ofNullable(ColonyFront.of(colony).settings(OrdersContent.ID).parameters().get(key));
        };
    }

    @Override
    public Optional<List<DefinedFacilities.Problem>> setParameter(Held held, String key, Value value) {
        Optional<ServerLevel> level = Optional.ofNullable(ServerLifecycleHooks.getCurrentServer())
            .flatMap(held.site()::level);
        Optional<Card.Element.Parameter> parameter = ELEMENTS.stream()
            .filter(Card.Element.Parameter.class::isInstance).map(Card.Element.Parameter.class::cast)
            .filter(element -> element.param().equals(key)).findFirst();
        if (parameter.isEmpty()) {
            return Optional.empty();
        }
        if (level.isEmpty() || !isContainer(level.get(), held.site())) {
            return problems(key, Attempt.refused(OrderRefusal.NOT_A_CONTAINER));
        }
        if (!parameter.get().accepts(value)) {
            return Optional.of(List.of(new DefinedFacilities.Problem(key, "parameter", key)));
        }
        WorldPos into = anchor(held, level.get());
        Shelf shelf = shelfAt(into);
        Shelf.Admitting filter = shelf.admitting().orElseGet(() -> new Shelf.Admitting(false, List.of()));
        if (key.equals(OrdersContent.FILTER.key())) {
            return problems(key, shelve(shelf.taking(Optional.of(
                new Shelf.Admitting(filter.only(), ((Value.Items) value).filters())))));
        }
        if (key.equals(FILTER_MODE)) {
            boolean only = ((Value.Text) value).value().equals(FILTER_ONLY);
            return problems(key, shelve(shelf.taking(Optional.of(new Shelf.Admitting(only, filter.filters())))));
        }
        if (key.equals(TAKE) || key.equals(STORE)) {
            boolean on = ((Value.Bool) value).on();
            return problems(key, shelve(new Shelf(into, shelf.admitting(),
                key.equals(TAKE) ? on : shelf.take(), key.equals(STORE) ? on : shelf.store())));
        }
        ColonySettings.ofParameters(Map.of(key, value)).valueOf(key)
            .ifPresent(next -> ColonyFront.of(colony).setSetting(OrdersContent.ID, key, next));
        return Optional.of(List.of());
    }

    private Shelf shelfAt(WorldPos into) {
        return shelves.getOrDefault(into, Shelf.none(into));
    }

    private Attempt shelve(Shelf shelf) {
        if (shelf.bare()) {
            shelves.remove(shelf.into());
        } else {
            shelves.put(shelf.into(), shelf);
        }
        refreshPending.set(true);
        write();
        return Attempt.went();
    }

    private Attempt file(WorldPos into, boolean standing, ColonyView view) {
        Settings draft = Fronts.of(colony).settings(OrdersContent.ID);
        Optional<ItemSpec> what = draft.items(OrdersContent.ITEM.key());
        if (what.isEmpty()) {
            return Attempt.refused(OrderRefusal.NO_ITEM);
        }
        if (Goods.stackSize(what.get()) <= 0) {
            return Attempt.refused(OrderRefusal.UNKNOWN_ITEM);
        }
        if (StoreAccess.at(view.level(), into.block(view.level())).isEmpty()
                || Containers.at(view.level(), into.block(view.level())).isEmpty()) {
            return Attempt.refused(OrderRefusal.NOT_A_CONTAINER);
        }
        Order order = new Order(into, what.get(), draft.count(OrdersContent.LOW.key()),
            draft.count(OrdersContent.COUNT.key()), standing);
        if (!orders.containsKey(order.key()) && orders.size() >= MAX_ORDERS) {
            return Attempt.refused(OrderRefusal.TOO_MANY, Notice.count(MAX_ORDERS));
        }
        orders.put(order.key(), order);
        observeTargets(view.level().getServer());
        filling.remove(order.key());
        pending.remove(order.key());
        refreshPending.set(true);
        write();
        return Attempt.went();
    }

    // A once order for count more than the container holds or is already promised, as a request from elsewhere
    // than its card files it: a storage terminal, say. Requests for goods already on order add to that order.
    Attempt request(ServerLevel level, WorldPos into, ItemSpec what, long count) {
        if (count < Order.MIN_COUNT) {
            return Attempt.refused(OrderRefusal.NO_COUNT);
        }
        if (Goods.stackSize(what) <= 0) {
            return Attempt.refused(OrderRefusal.UNKNOWN_ITEM);
        }
        if (!into.in(level) || StoreAccess.at(level, into.block(level)).isEmpty()
                || Containers.at(level, into.block(level)).isEmpty()) {
            return Attempt.refused(OrderRefusal.NOT_A_CONTAINER);
        }
        Order.Key key = new Order.Key(into, what);
        Order existing = orders.get(key);
        if (existing != null && existing.standing()) {
            return Attempt.refused(OrderRefusal.STANDING);
        }
        long base;
        if (existing != null) {
            base = existing.high();
        } else {
            var observed = colony.inventory().snapshot().store(into)
                .filter(store -> store.state() == InventorySnapshot.State.OBSERVED);
            if (observed.isEmpty()) {
                colony.inventory().ensureObserved(Stash.at(into));
                return Attempt.refused(OrderRefusal.COUNTING);
            }
            base = held(observed.get(), what);
            if (orders.size() >= MAX_ORDERS) {
                return Attempt.refused(OrderRefusal.TOO_MANY, Notice.count(MAX_ORDERS));
            }
        }
        if (base + count > Order.MAX_COUNT) {
            return Attempt.refused(OrderRefusal.TOO_LARGE, Notice.count(Math.max(0, Order.MAX_COUNT - base)));
        }
        int high = (int) (base + count);
        orders.put(key, new Order(into, what, high, high, false));
        observeTargets(level.getServer());
        filling.remove(key);
        pending.remove(key);
        failed.remove(key);
        refreshPending.set(true);
        write();
        return Attempt.went();
    }

    Attempt cancel(WorldPos into, ItemSpec what) {
        return withdraw(into, what.describe());
    }

    // Each order into these containers as a request reads it: what it fills to, what is there and on the way, whether
    // work on it has begun, and why it stands still if it does.
    List<Orders.Request> requests(ServerLevel level, java.util.Collection<WorldPos> into) {
        InventorySnapshot inventory = colony.inventory().snapshot();
        List<Stall> stalls = colony.stallsIn(level.dimension());
        List<Orders.Request> found = new ArrayList<>();
        for (Order order : orders.values()) {
            if (!into.contains(order.into())) {
                continue;
            }
            long present = inventory.store(order.into())
                .filter(store -> store.state() == InventorySnapshot.State.OBSERVED)
                .map(store -> held(store, order.what())).orElse(0L);
            Filling delivery = filling.get(order.key());
            boolean begun = delivery != null && (delivery.begun() || colony.holder(delivery.id).isPresent());
            Optional<Notice> stalled = Optional.ofNullable(failed.get(order.key())).map(Notice::of)
                .or(() -> stalls.stream().filter(stall -> stall.where().equals(order.into()))
                    .filter(stall -> stall.goods().map(goods -> Goods.overlap(goods, order.what())).orElse(true))
                    .findFirst().map(ColonyStalls::notice));
            found.add(new Orders.Request(order.into(), order.what(), order.high(), order.standing(), present,
                onTheWay(order), begun, stalled));
        }
        return List.copyOf(found);
    }

    private Attempt withdraw(WorldPos into, String goods) {
        for (Order order : List.copyOf(orders.values())) {
            if (!order.into().equals(into) || !order.what().describe().equals(goods)) {
                continue;
            }
            orders.remove(order.key());
            filling.remove(order.key());
            pending.remove(order.key());
            refreshPending.set(true);
            write();
            return Attempt.went();
        }
        return Attempt.refused(OrderRefusal.NOT_A_CONTAINER);
    }

    private final AtomicBoolean refreshPending =
        new AtomicBoolean(true);

    // When orders are read again within each period, spread against every other cron; see Cadence.
    private final Cadence.Cron sweeping;

    public void tick(MinecraftServer server) {
        if (sweeping.due(server)) {
            refreshPending.set(true);
        }
        if (refreshPending.getAndSet(false)) {
            refresh(server);
        }
    }

    void refresh(MinecraftServer server) {
        settleCompleted();
        observeTargets(server);
        InventorySnapshot inventory = colony.inventory().snapshot();
        Set<ServerLevel> levels = new LinkedHashSet<>();
        for (Order order : orders.values()) {
            order.into().level(server).ifPresent(levels::add);
        }
        Map<ResourceKey<Level>, List<Grown>> work = new LinkedHashMap<>();
        for (ServerLevel level : levels) {
            work.put(level.dimension(), inspect(level, inventory));
        }
        stated = Map.copyOf(work);
        asking.offer(stated);
        rule(server);
        write();
    }

    private void observeTargets(MinecraftServer server) {
        observedServer = server;
        for (ServerLevel level : server.getAllLevels()) {
            Set<WorldPos> endpoints = new LinkedHashSet<>();
            orders.values().stream().map(Order::into).filter(at -> at.in(level)).forEach(endpoints::add);
            shelves.keySet().stream().filter(at -> at.in(level)).forEach(endpoints::add);
            colony.inventory().observe(OrdersContent.ID, level, endpoints);
        }
    }

    // Standing orders are reserves; shelves govern access and incoming goods.
    private void rule(MinecraftServer server) {
        InventorySnapshot inventory = colony.inventory().snapshot();
        boolean pending = false;
        for (Shelf shelf : shelves.values()) {
            if (shelf.into().level(server)
                    .filter(level -> StoreAccess.unavailable(level, shelf.into().block(level))).isPresent()) {
                continue;
            }
            var observed = inventory.store(shelf.into());
            if (observed.isEmpty() || observed.get().state() != InventorySnapshot.State.OBSERVED) {
                colony.inventory().ensureObserved(Stash.at(shelf.into()));
                pending = true;
            }
        }
        if (pending) {
            return;
        }
        Map<ResourceKey<Level>, List<StoreRule>> rules = new LinkedHashMap<>();
        for (Order order : orders.values()) {
            if (order.standing()) {
                order.into().level(server).ifPresent(level -> rules
                    .computeIfAbsent(level.dimension(), key -> new ArrayList<>())
                    .add(new StoreRule.Reserve(anchored(order.into()), order.what())));
            }
        }
        for (Shelf shelf : List.copyOf(shelves.values())) {
            Optional<ServerLevel> in = shelf.into().level(server);
            if (in.isEmpty()) {
                continue;
            }
            ServerLevel level = in.get();
            rules.computeIfAbsent(level.dimension(), key -> new ArrayList<>())
                .addAll(shelf.rules(anchored(shelf.into())));
        }
        for (ResourceKey<Level> dimension : ruledIn) {
            if (!rules.containsKey(dimension)) {
                colony.rule(OrdersContent.ID, dimension, List.of());
            }
        }
        rules.forEach((dimension, here) -> colony.rule(OrdersContent.ID, dimension, here));
        ruledIn = Set.copyOf(rules.keySet());
    }

    // Either half of a double chest names the same store as the colony does: the lower corner.
    private WorldPos anchored(WorldPos where) {
        return colony.inventory().snapshot().store(where).map(store -> store.stash().pos()).orElse(where);
    }

    private void settleCompleted() {
        for (Order.Key key : List.copyOf(filling.keySet())) {
            if (!filling.get(key).complete()) {
                continue;
            }
            filling.remove(key);
            pending.remove(key);
            Order order = orders.get(key);
            if (order != null && !order.standing()) {
                orders.remove(key);
            }
        }
    }

    public void closed(Closing why) {
        if (observedServer != null) {
            for (ServerLevel level : observedServer.getAllLevels()) {
                colony.inventory().observe(OrdersContent.ID, level, List.of());
            }
        }
        if (why == Closing.RAZED) {
            orders.clear();
            shelves.clear();
            filling.clear();
            pending.clear();
        } else {
            write();
        }
    }

    public List<Grown> goals(ColonyView view) {
        return stated.getOrDefault(view.level().dimension(), List.of());
    }

    private List<Grown> inspect(ServerLevel level, InventorySnapshot inventory) {
        List<Grown> wanted = new ArrayList<>();
        for (Order order : List.copyOf(orders.values())) {
            if (!order.into().in(level)) {
                continue;
            }
            if (StoreAccess.unavailable(level, order.into().block(level))) {
                Filling delivery = filling.remove(order.key());
                if (delivery != null && delivery.remaining() > 0) {
                    pending.put(order.key(), delivery.remaining());
                }
                continue;
            }
            var observed = inventory.store(order.into());
            if (observed.filter(store -> store.state() == InventorySnapshot.State.UNSUPPORTED).isPresent()) {
                colony.inventory().ensureObserved(Stash.at(order.into()));
                continue;
            }
            if (observed.isPresent() && !observed.get().usability().usable()) {
                continue;
            }
            // A once order is done when its target holds enough, even if the goods came by another route.
            if (!order.standing() && observed.filter(store -> store.state() == InventorySnapshot.State.OBSERVED)
                    .map(store -> held(store, order) >= order.high()).orElse(false)) {
                orders.remove(order.key());
                filling.remove(order.key());
                pending.remove(order.key());
                continue;
            }
            Filling delivery = filling.get(order.key());
            if (delivery != null && delivery.complete()) {
                continue;
            }
            if (delivery == null) {
                if (observed.isEmpty() || observed.get().state() != InventorySnapshot.State.OBSERVED) {
                    colony.inventory().ensureObserved(Stash.at(order.into()));
                    continue;
                }
                var target = observed.get();
                long held = held(target, order);
                if (order.standing()) { held += OrderReturns.count(colony, order.into(), order.what()); }
                long count = pending.getOrDefault(order.key(), order.replenishment(held));
                if (count <= 0) {
                    if (!order.standing() && held >= order.high()) {
                        orders.remove(order.key());
                    }
                    continue;
                }
                Optional<Stances> stands = target.approach().state() == InventorySnapshot.State.OBSERVED
                    ? Stances.of(target.approach().putting().stream().map(Stand::cell).toList()) : Optional.empty();
                if (stands.isEmpty()) {
                    continue;
                }
                Order.Key key = order.key();
                delivery = new Filling(new WorkSite.AtBlock(order.into()), stands.get(), order.what(), count,
                    () -> {
                        failed.remove(key);
                        refreshPending.set(true);
                    });
                filling.put(order.key(), delivery);
                pending.remove(order.key());
            }
            wanted.add(delivery.goal());
        }
        write();
        return List.copyOf(wanted);
    }

    private static long held(InventorySnapshot.Store store, Order order) {
        return held(store, order.what());
    }

    private static long held(InventorySnapshot.Store store, ItemSpec what) {
        return !store.usability().usable() ? 0 : store.entries().stream()
            .filter(entry -> Goods.matches(entry.prototype(), what)).mapToLong(entry -> entry.count()).sum();
    }

    @Override
    public List<Line> lines(Held held, ColonyView view) {
        if (!held.site().in(view.level()) || !isContainer(view.level(), held.site())) {
            return List.of();
        }
        return shelfAt(anchor(held, view.level())).admitting().map(filter -> List.of(Line.text(
            Component.translatable(filter.only() ? "folkways.panel.orders.filter.only.count"
                : "folkways.panel.orders.filter.except.count",
                filter.goods().map(spec -> Goods.members(spec).size()).orElse(0))))).orElseGet(List::of);
    }

    @Override
    public List<Card.Row> rows(Page page, ColonyView view) {
        if (page != Page.STOCK) {
            return List.of();
        }
        InventorySnapshot inventory = colony.inventory().snapshot();
        return orders.values().stream().map(order -> {
            Card.Row row = orderRow(view.level(), order, inventory,
                List.of(Line.text(containerName(view.level(), order.into()))));
            return WorldSpaces.storage(view.level(), order.into()).map(row::at).orElse(row);
        }).toList();
    }

    @Override
    public List<Card.Row> rows(Held held, String source, ColonyView view) {
        if (!source.equals(HERE) || !held.site().in(view.level()) || !isContainer(view.level(), held.site())) {
            return List.of();
        }
        WorldPos into = anchor(held, view.level());
        InventorySnapshot inventory = colony.inventory().snapshot();
        return orders.values().stream().filter(order -> order.into().equals(into))
            .map(order -> orderRow(view.level(), order, inventory, List.of()).with(new Card.Act.Run(
                WITHDRAW, Map.of("goods", new Value.Text(order.what().describe())),
                label("folkways.action.delete"), Optional.of(icon("barrier"))))).toList();
    }

    // One order on a line of its own: what it keeps, then whether it stands or comes once, the counts it keeps to, how
    // many the container holds now and how many are still on their way; in pictures, with the words for the rest.
    private Card.Row orderRow(ServerLevel level, Order order, InventorySnapshot inventory, List<Line> context) {
        List<Line> details = new ArrayList<>(context);
        if (order.into().in(level) && StoreAccess.unavailable(level, order.into().block(level))) {
            String reason = StoreAccess.attached(level, order.into().block(level))
                ? "folkways.store.unsupported" : "folkways.refusal.haul.facility_part";
            details.add(Line.text(Component.translatable(reason)));
            return Card.Row.of(iconOf(order.what()), Line.text(nameOf(order.what())), details);
        }
        var target = inventory.store(order.into());
        Optional<Long> present = target.filter(store -> store.state() == InventorySnapshot.State.OBSERVED)
            .map(store -> held(store, order));
        long left = onTheWay(order);
        if (present.isEmpty()) {
            colony.inventory().ensureObserved(Stash.at(order.into()));
        }
        Component detail = present.isEmpty() ? Component.translatable("folkways.panel.orders.unknown")
            : detailOf(order, present, left);
        details.add(new Line.Text(detail, present.map(here -> toldOf(order, here, left)).orElse(Sentence.EMPTY)));
        return Card.Row.of(iconOf(order.what()), Line.text(nameOf(order.what())), details);
    }

    private long onTheWay(Order order) {
        Filling delivery = filling.get(order.key());
        return delivery == null ? pending.getOrDefault(order.key(), 0L) : delivery.remaining();
    }

    private static Component detailOf(Order order, Optional<Long> present, long left) {
        if (present.isEmpty()) {
            return Component.translatable("folkways.panel.orders.unknown");
        }
        MutableComponent detail = order.standing()
            ? Component.translatable("folkways.panel.orders.standing", present.get(), order.low(), order.high())
            : Component.translatable("folkways.panel.orders.once", present.get(), order.high());
        if (left > 0) {
            detail.append(Component.translatable("folkways.panel.orders.coming", left));
        }
        return detail;
    }

    // "✗ [repeater] [stock]40 + 32 / 16–64": standing or once, by the button that files it, then what the container
    // holds, plus what is on its way, against the counts it keeps to; marked short while it lacks and nothing comes.
    private static Sentence toldOf(Order order, long present, long left) {
        List<Sentence.Token> tokens = new ArrayList<>();
        if (left == 0 && present < (order.standing() ? order.low() : order.high())) {
            tokens.add(Sentence.glyph("lacks"));
        }
        tokens.add(Sentence.ware(icon(order.standing() ? "repeater" : "chest_minecart"), 0));
        tokens.add(Sentence.glyph("stock"));
        tokens.add(Sentence.word(Notice.count(present)));
        if (left > 0) {
            tokens.add(Sentence.PLUS);
            tokens.add(Sentence.word(Notice.count(left)));
        }
        tokens.add(Sentence.glyph("slash"));
        tokens.add(Sentence.word(order.standing()
            ? Notice.text(order.low() + "–" + order.high())
            : Notice.count(order.high())));
        return new Sentence(tokens);
    }

    // A container by the block it is and where it stands.
    private static Component containerName(ServerLevel level, WorldPos into) {
        Optional<BlockPos> storage = WorldSpaces.storage(level, into);
        BlockPos cell = storage.orElse(into.cell());
        Component block = storage.filter(level::isLoaded).map(at -> BlockNames.at(level, at))
            .orElseGet(() -> Component.translatable("folkways.panel.orders.container.away"));
        return Component.translatable("folkways.panel.orders.container", block, cell.getX(), cell.getY(), cell.getZ());
    }

    private static Card.Label label(String key) {
        return new Card.Label(key, true);
    }

    private static ResourceLocation icon(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    private static ItemStack iconOf(ItemSpec spec) {
        return Goods.display(spec);
    }

    private static Component nameOf(ItemSpec spec) {
        if (spec.item().isEmpty()) {
            return Component.literal(spec.describe());
        }
        ItemStack icon = iconOf(spec);
        return icon.isEmpty() ? Component.literal(spec.describe()) : icon.getHoverName();
    }
}
