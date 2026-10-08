package io.github.izakyl.folkways.plugins.orders;

import io.github.izakyl.folkways.core.api.colony.Asking;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.front.api.notice.Attempt;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import io.github.izakyl.folkways.front.api.panel.Board;
import java.util.ArrayList;
import java.util.Comparator;
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
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

final class OrdersPresence implements Facing {

    static final int MAX_ORDERS = 256;

    private static final int SWEEP_TICKS = 20;

    private static final String TAG_ORDERS = "orders";
    private static final String TAG_SHELVES = "shelves";

    private final Colony colony;

    private final Asking asking;
    private CompoundTag saved;
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
        private final Runnable delivered;

        Filling(WorkSite into, Stances stances, ItemSpec what, long count, Runnable delivered) {
            this.into = into;
            this.stances = stances;
            this.what = what;
            this.remaining = new AtomicLong(count);
            this.delivered = delivered;
        }

        long remaining() {
            return remaining.get();
        }

        boolean complete() {
            return remaining.get() <= 0;
        }

        Grown goal(int rank) {
            return Grown.of(Delivery.to(id, OrdersContent.ID, into, stances, what, remaining.get(),
                count -> {
                    remaining.accumulateAndGet(count, (left, arrived) -> Math.max(0, left - arrived));
                    delivered.run();
                }))
                .ranked(rank);
        }
    }

    private final Map<Order.Key, Filling> filling = new LinkedHashMap<>();

    private final Map<Order.Key, Long> pending = new LinkedHashMap<>();

    OrdersPresence(Colony colony) {
        this.colony = colony;
        this.asking = new Asking(colony, OrdersContent.ID, () -> refreshPending.set(true));
        saved = colony.kept(OrdersContent.ID);
        Reader kept = Reader.of(saved);
        for (Reader saved : kept.children(TAG_ORDERS)) {
            Order.load(saved).ifPresent(order -> {
                orders.put(order.key(), order);
                saved.longValue("remaining").filter(left -> left > 0)
                    .ifPresent(left -> pending.put(order.key(), left));
            });
        }
        for (Reader saved : kept.children(TAG_SHELVES)) {
            Shelf.load(saved).ifPresent(shelf -> shelves.put(shelf.into(), shelf));
        }
    }

    private void write() {
        settleCompleted();
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

    @Override
    public Attempt act(ResourceLocation page, String actionKey, Optional<BlockPos> at,
            ColonyView view) {
        Optional<OrderAct> act = OrderAct.parse(actionKey);
        if (!page.equals(OrdersContent.PAGE.id()) || at.isEmpty() || act.isEmpty()) {
            return Attempt.refused(OrderRefusal.NOT_A_CONTAINER);
        }
        WorldPos into = WorldPos.of(view.level(), Containers.anchor(view.level(), at.get()));
        return switch (act.get()) {
            case OrderAct.Maintain ignored -> file(into, true, view);
            case OrderAct.Deliver ignored -> file(into, false, view);
            case OrderAct.Withdraw withdraw -> withdraw(into, withdraw.goods());
            case OrderAct.Admit admit -> admit(into, admit.only(), view);
            case OrderAct.AdmitAny ignored -> shelve(shelfAt(into).taking(Optional.empty()));
            case OrderAct.Slots ignored -> slots(into, view);
            case OrderAct.AllSlots ignored -> shelve(shelfAt(into).spanning(Optional.empty()));
        };
    }

    private Attempt admit(WorldPos into, boolean only, ColonyView view) {
        Optional<ItemSpec> what = Fronts.of(colony).settings(OrdersContent.ID).items(OrdersContent.ITEM.key());
        if (what.isEmpty() || Goods.members(what.get()).isEmpty()) {
            return Attempt.refused(OrderRefusal.UNKNOWN_ITEM);
        }
        if (Containers.at(view.level(), into.block(view.level())).isEmpty()) {
            return Attempt.refused(OrderRefusal.NOT_A_CONTAINER);
        }
        Shelf next = shelfAt(into).taking(Optional.of(new Shelf.Admitting(only, what.get())));
        for (Order order : ordersAt(into)) {
            if (!next.admits(order.what())) {
                return Attempt.refused(OrderRefusal.ORDERED_HERE);
            }
        }
        return shelve(next);
    }

    private Attempt slots(WorldPos into, ColonyView view) {
        if (Containers.at(view.level(), into.block(view.level())).isEmpty()) {
            return Attempt.refused(OrderRefusal.NOT_A_CONTAINER);
        }
        Settings draft = Fronts.of(colony).settings(OrdersContent.ID);
        return shelve(shelfAt(into).spanning(Optional.of(Shelf.Span.between(
            draft.count(OrdersContent.FIRST_SLOT.key()), draft.count(OrdersContent.LAST_SLOT.key())))));
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
        if (what.isEmpty() || Goods.stackSize(what.get()) <= 0) {
            return Attempt.refused(OrderRefusal.UNKNOWN_ITEM);
        }
        if (Containers.at(view.level(), into.block(view.level())).isEmpty()) {
            return Attempt.refused(OrderRefusal.NOT_A_CONTAINER);
        }
        if (!shelfAt(into).admits(what.get())) {
            return Attempt.refused(OrderRefusal.NOT_ADMITTED);
        }
        Order order = new Order(into, what.get(), draft.count(OrdersContent.LOW.key()),
            draft.count(OrdersContent.COUNT.key()), standing, draft.count(OrdersContent.RANK.key()));
        if (!orders.containsKey(order.key()) && orders.size() >= MAX_ORDERS) {
            return Attempt.refused(OrderRefusal.TOO_MANY, Notice.count(MAX_ORDERS));
        }
        orders.put(order.key(), order);
        filling.remove(order.key());
        pending.remove(order.key());
        refreshPending.set(true);
        write();
        return Attempt.went();
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

    private int sinceSweep;

    public void tick(MinecraftServer server) {
        if (++sinceSweep >= SWEEP_TICKS) {
            sinceSweep = 0;
            refreshPending.set(true);
        }
        if (refreshPending.getAndSet(false)) {
            refresh(server);
        }
    }

    void refresh(MinecraftServer server) {
        settleCompleted();
        Set<ServerLevel> levels = new LinkedHashSet<>();
        for (Order order : orders.values()) {
            order.into().level(server).ifPresent(levels::add);
        }
        Map<ResourceKey<Level>, List<Grown>> work = new LinkedHashMap<>();
        for (ServerLevel level : levels) {
            work.put(level.dimension(), inspect(level));
        }
        stated = Map.copyOf(work);
        asking.offer(stated);
        rule(server);
        write();
    }

    // Standing orders are reserves, and every shelf says what its container takes and which slots are ours.
    private void rule(MinecraftServer server) {
        Map<ResourceKey<Level>, List<StoreRule>> rules = new LinkedHashMap<>();
        for (Order order : orders.values()) {
            if (order.standing()) {
                order.into().level(server).ifPresent(level -> rules
                    .computeIfAbsent(level.dimension(), key -> new ArrayList<>())
                    .add(new StoreRule.Reserve(anchored(level, order.into()), order.what())));
            }
        }
        for (Shelf shelf : List.copyOf(shelves.values())) {
            Optional<ServerLevel> in = shelf.into().level(server);
            if (in.isEmpty()) {
                continue;
            }
            ServerLevel level = in.get();
            if (Containers.gone(level, shelf.into().block(level))) {
                shelves.remove(shelf.into());
                continue;
            }
            rules.computeIfAbsent(level.dimension(), key -> new ArrayList<>())
                .addAll(shelf.rules(anchored(level, shelf.into())));
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
    private static WorldPos anchored(ServerLevel level, WorldPos where) {
        BlockPos cell = where.block(level);
        return level.isLoaded(cell) ? WorldPos.of(level, Containers.anchor(level, cell)) : where;
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

    private List<Grown> inspect(ServerLevel level) {
        List<Grown> wanted = new ArrayList<>();
        for (Order order : List.copyOf(orders.values())) {
            if (!order.into().in(level)) {
                continue;
            }
            if (Containers.gone(level, order.into().block(level))) {
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
                Optional<Container> target = Containers.at(level, order.into().block(level));
                if (target.isEmpty()) {
                    continue;
                }
                long held = Goods.countIn(target.get(), order.what());
                long count = pending.getOrDefault(order.key(), order.replenishment(held));
                if (count <= 0) {
                    if (!order.standing() && held >= order.high()) {
                        orders.remove(order.key());
                    }
                    continue;
                }
                Optional<Stances> stands = Stances.of(level,
                    Reach.workableCells(level, order.into().block(level)));
                if (stands.isEmpty()) {
                    continue;
                }
                delivery = new Filling(new WorkSite.AtBlock(order.into()), stands.get(), order.what(), count,
                    () -> refreshPending.set(true));
                filling.put(order.key(), delivery);
                pending.remove(order.key());
            }
            wanted.add(delivery.goal(order.rank()));
        }
        write();
        return List.copyOf(wanted);
    }

    private static Optional<Container> containerOf(Order order, MinecraftServer server) {
        return order.into().level(server)
            .flatMap(level -> Containers.at(level, order.into().block(level)));
    }

    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(OrdersContent.PAGE.id())) {
            return Optional.empty();
        }
        MinecraftServer server = view.level().getServer();
        List<Board.Row> rows = new ArrayList<>();
        rows.add(draftRow());
        for (Order order : orders.values()) {
            rows.add(new Board.Row(iconOf(order.what()),
                Component.translatable("folkways.panel.orders.row", order.high(),
                    nameOf(order.what())),
                progressOf(order, server),
                WorldSpaces.storage(view.level(), order.into())
                    .map(cell -> List.<Board.Act>of(new Board.Act.Ping(cell))).orElse(List.of()))
                .told(toldOf(order, server)));
        }
        return Optional.of(new Board(
            List.of(new Board.Figure("folkways.panel.orders.open",
                Component.literal(Integer.toString(orders.size())))),
            rows,
            Optional.empty()));
    }

    @Override
    public Optional<Board> boardAt(ResourceLocation page, BlockPos at, ColonyView view) {
        ServerLevel level = view.level();
        if (!page.equals(OrdersContent.PAGE.id()) || Containers.at(level, at).isEmpty()) {
            return Optional.empty();
        }
        WorldPos into = WorldPos.of(level, Containers.anchor(level, at));
        List<Order> here = ordersAt(into);
        List<Board.Row> rows = new ArrayList<>();
        rows.add(draftRow());
        rows.add(new Board.Row(ItemStack.EMPTY,
            Component.translatable("folkways.panel.orders.file"),
            Component.empty(),
            List.of(new Board.Act.Do(new OrderAct.Maintain().key(), "folkways.orders.maintain"),
                new Board.Act.Do(new OrderAct.Deliver().key(), "folkways.orders.deliver"))));
        for (Order order : here) {
            rows.add(new Board.Row(iconOf(order.what()),
                Component.translatable("folkways.panel.orders.row", order.high(),
                    nameOf(order.what())),
                progressOf(order, level.getServer()),
                List.of(new Board.Act.Do(OrderAct.withdrawing(order.what()).key(),
                    "folkways.action.delete")))
                .told(toldOf(order, level.getServer())));
        }
        rows.addAll(shelfRows(shelfAt(into)));
        return Optional.of(new Board(
            List.of(new Board.Figure("folkways.panel.orders.here",
                Component.literal(Integer.toString(here.size())))),
            rows,
            Optional.empty()));
    }

    private static List<Board.Row> shelfRows(Shelf shelf) {
        List<Board.Row> rows = new ArrayList<>();
        rows.add(Board.Row.heading(Component.translatable("folkways.panel.orders.shelf")));
        List<Board.Act> admitting = new ArrayList<>(List.of(
            new Board.Act.Do(new OrderAct.Admit(true).key(), "folkways.orders.admit_only"),
            new Board.Act.Do(new OrderAct.Admit(false).key(), "folkways.orders.admit_except")));
        if (shelf.admitting().isPresent()) {
            admitting.add(new Board.Act.Do(new OrderAct.AdmitAny().key(), "folkways.orders.admit_any"));
        }
        rows.add(new Board.Row(
            shelf.admitting().map(admit -> iconOf(admit.goods())).orElse(ItemStack.EMPTY),
            Component.translatable("folkways.panel.orders.admits"),
            shelf.admitting()
                .map(admit -> Component.translatable(admit.only()
                    ? "folkways.panel.orders.admits.only" : "folkways.panel.orders.admits.except",
                    nameOf(admit.goods())))
                .orElse(Component.translatable("folkways.panel.orders.admits.any")),
            admitting)
            .told(shelf.admitting()
                .map(admit -> admitsTold(admit.only(), admit.goods()))
                .orElse(Sentence.EMPTY)));
        List<Board.Act> spanning = new ArrayList<>(List.of(
            new Board.Act.Edit(OrdersContent.FIRST_SLOT.key()),
            new Board.Act.Edit(OrdersContent.LAST_SLOT.key()),
            new Board.Act.Do(new OrderAct.Slots().key(), "folkways.orders.slots")));
        if (shelf.slots().isPresent()) {
            spanning.add(new Board.Act.Do(new OrderAct.AllSlots().key(), "folkways.orders.all_slots"));
        }
        rows.add(new Board.Row(ItemStack.EMPTY,
            Component.translatable("folkways.panel.orders.slots"),
            shelf.slots()
                .map(span -> Component.translatable("folkways.panel.orders.slots.span", span.first(), span.last()))
                .orElse(Component.translatable("folkways.panel.orders.slots.all")),
            spanning));
        return rows;
    }

    private List<Order> ordersAt(WorldPos into) {
        List<Order> here = new ArrayList<>();
        for (Order order : orders.values()) {
            if (order.into().equals(into)) {
                here.add(order);
            }
        }
        return List.copyOf(here);
    }

    private Board.Row draftRow() {
        Settings draft = Fronts.of(colony).settings(OrdersContent.ID);
        Optional<ItemSpec> what = draft.items(OrdersContent.ITEM.key());
        return new Board.Row(
            what.map(OrdersPresence::iconOf).orElse(ItemStack.EMPTY),
            Component.translatable("folkways.panel.orders.draft"),
            Component.translatable("folkways.panel.orders.draft.detail",
                draft.count(OrdersContent.LOW.key()),
                draft.count(OrdersContent.COUNT.key()),
                what.map(OrdersPresence::nameOf).orElse(Component.empty())),
            List.of(new Board.Act.Edit(OrdersContent.ITEM.key()),
                new Board.Act.Edit(OrdersContent.LOW.key()),
                new Board.Act.Edit(OrdersContent.COUNT.key()),
                new Board.Act.Edit(OrdersContent.RANK.key())))
            .told(what.flatMap(spec -> wareOf(spec, 0))
                .map(ware -> Sentence.of(ware, span(draft.count(OrdersContent.LOW.key()),
                    draft.count(OrdersContent.COUNT.key()))))
                .orElse(Sentence.EMPTY));
    }

    /**
     * An order in pictures: a standing one is the stock of its item kept against the span it keeps, a one-time one
     * the item delivered against all it asks for, either then its priority and what is still on its way.
     */
    private Sentence toldOf(Order order, MinecraftServer server) {
        Optional<Container> held = containerOf(order, server);
        if (held.isEmpty()) {
            return Sentence.of(Sentence.glyph("lacks"),
                Sentence.word(new Notice("folkways.panel.orders.gone", List.of())));
        }
        long present = Goods.countIn(held.get(), order.what());
        Optional<Sentence.Token.Ware> have = wareOf(order.what(), present);
        if (have.isEmpty()) {
            return Sentence.EMPTY;
        }
        List<Sentence.Token> tokens = new ArrayList<>();
        if (order.standing()) {
            tokens.add(Sentence.glyph("stock"));
        }
        tokens.add(have.get());
        if (present <= 0) {
            tokens.add(Sentence.glyph("lacks"));
        }
        tokens.add(Sentence.glyph("slash"));
        tokens.add(order.standing() ? span(order.low(), order.high())
            : Sentence.word(Notice.count(order.high())));
        tokens.add(Sentence.word(new Notice("folkways.panel.orders.told.rank",
            List.of(Notice.count(order.rank())))));
        Filling delivery = filling.get(order.key());
        long left = delivery == null ? pending.getOrDefault(order.key(), 0L) : delivery.remaining();
        if (left > 0) {
            tokens.add(Sentence.glyph("remaining"));
            tokens.add(wareOf(order.what(), left).orElseThrow());
        }
        return new Sentence(tokens);
    }

    private static Sentence admitsTold(boolean only, ItemSpec goods) {
        List<Sentence.Token.Ware> wares = new ArrayList<>();
        List<ItemSpec> each = goods.anyOf().isEmpty() ? List.of(goods)
            : goods.anyOf().stream().sorted(Comparator.comparing(ItemSpec::describe)).toList();
        for (ItemSpec spec : each) {
            wareOf(spec, 0).ifPresent(wares::add);
        }
        return wares.isEmpty() ? Sentence.EMPTY : Sentence.wares(Sentence.glyph(only ? "admits" : "refuses"), wares);
    }

    private static Sentence.Token span(long low, long high) {
        return Sentence.word(new Notice("folkways.panel.orders.told.span",
            List.of(Notice.count(low), Notice.count(high))));
    }

    private static Optional<Sentence.Token.Ware> wareOf(ItemSpec spec, long count) {
        ItemStack icon = iconOf(spec);
        return icon.isEmpty() ? Optional.empty()
            : Optional.of(new Sentence.Token.Ware(BuiltInRegistries.ITEM.getKey(icon.getItem()), count));
    }

    private static Component progressOf(Order order, MinecraftServer server) {
        Optional<Container> held = containerOf(order, server);
        if (held.isEmpty()) {
            return Component.translatable("folkways.panel.orders.gone");
        }
        long present = Goods.countIn(held.get(), order.what());
        return order.standing()
            ? Component.translatable("folkways.panel.orders.standing", present,
                order.low(), order.high(), order.rank())
            : Component.translatable("folkways.panel.orders.once", present,
                order.high(), order.rank());
    }

    private static ItemStack iconOf(ItemSpec spec) {
        if (!spec.anyOf().isEmpty()) {
            return spec.anyOf().stream().min(Comparator.comparing(ItemSpec::describe))
                .map(OrdersPresence::iconOf).orElse(ItemStack.EMPTY);
        }
        if (spec.tag().isPresent()) {
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTag(spec.tag().get())
                .map(named -> (Iterable<Holder<Item>>) named).orElseGet(List::of)) {
                return new ItemStack(holder.value());
            }
            return ItemStack.EMPTY;
        }
        return spec.item().flatMap(BuiltInRegistries.ITEM::getOptional)
            .map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    private static Component nameOf(ItemSpec spec) {
        if (spec.item().isEmpty()) {
            return Component.literal(spec.describe());
        }
        ItemStack icon = iconOf(spec);
        return icon.isEmpty() ? Component.literal(spec.describe()) : icon.getHoverName();
    }
}
