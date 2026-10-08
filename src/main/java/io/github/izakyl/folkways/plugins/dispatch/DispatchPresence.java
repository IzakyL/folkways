package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.notice.Attempt;
import io.github.izakyl.folkways.front.api.notice.Line;
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
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class DispatchPresence implements Facing {

    private static final String TAG_NETWORKS = "networks";

    private static final ResourceLocation DISPATCHING =
        ResourceLocation.fromNamespaceAndPath("folkways", "vocation/dispatching");
    private static final ResourceLocation PORT = ResourceLocation.fromNamespaceAndPath("folkways", "delegation/port");
    private static final ResourceLocation PARCEL =
        ResourceLocation.fromNamespaceAndPath("create", "cardboard_package_12x12");

    private final Colony colony;

    private final Sweep sweep;

    private final Networks networks = new Networks();
    private final Map<UUID, CollectNode> collecting = new ConcurrentHashMap<>();
    private final Eta eta = new Eta();

    private final Map<WorldPos, KeeperPosts> keeping = new LinkedHashMap<>();

    private record Standing(UUID id, Stances stances) {
    }

    private final Map<WorldPos, Standing> standings = new LinkedHashMap<>();

    private volatile NetworkSnapshot seen = NetworkSnapshot.empty();

    private volatile Map<WorldPos, Stances> strays = Map.of();

    // Which network each of our seated keepers is at the desk of.
    private volatile Map<UUID, UUID> keepers = Map.of();

    DispatchPresence(Colony colony) {
        this.colony = colony;
        this.sweep = new Sweep(colony, DispatchContent.ID, 20);
        Reader kept = Reader.of(colony.kept(DispatchContent.ID));
        kept.child(TAG_NETWORKS).ifPresent(networks::load);
    }

    public boolean take(UUID network) {
        if (!networks.take(network)) {
            return false;
        }
        remember();
        return true;
    }

    public boolean release(UUID network) {
        if (!networks.drop(network)) {
            return false;
        }
        remember();
        return true;
    }

    public boolean holds(UUID network) {
        return networks.holds(network);
    }

    public void tick(MinecraftServer server) {
        sweep.tick(server, this::refresh, this::goals);
    }

    void refresh(MinecraftServer server) {
        PackageNetwork network = PackageNetworks.get();
        long now = server.overworld().getGameTime();

        boolean unwired = false;
        for (UUID id : networks.all()) {
            if (!network.exists(server, id)) {
                unwired |= networks.drop(id);
            }
        }
        if (unwired) {
            remember();
        }

        List<ColonyView> views = colony.views(server);
        tend(views, now);
        Set<UUID> manned = new LinkedHashSet<>();
        Map<UUID, UUID> seated = new LinkedHashMap<>();
        Map<WorldPos, List<Post>> desks = desks(views, network, manned, seated);
        keepers = Map.copyOf(seated);
        Map<UUID, NetworkSnapshot.Reach> reach = new LinkedHashMap<>();
        for (UUID id : networks.all()) {
            Optional<PackageNetwork.Flaw> flaw = Optional.empty();
            for (ColonyView view : views) {
                flaw = flaw.or(() -> network.flawOf(view.level(), id));
            }
            reach.put(id, new NetworkSnapshot.Reach(network.summaryOf(server, id), manned.contains(id), flaw));
        }

        Set<WorldPos> present = new LinkedHashSet<>();
        Map<WorldPos, NetworkSnapshot.Port> read = new LinkedHashMap<>();
        Map<ServerLevel, List<WorldPos>> byLevel = new LinkedHashMap<>();
        for (ColonyView view : views) {
            Map<WorldPos, Set<UUID>> found = portsIn(view, network);
            byLevel.put(view.level(), List.copyOf(found.keySet()));
            for (Map.Entry<WorldPos, Set<UUID>> port : found.entrySet()) {
                present.add(port.getKey());
                read.put(port.getKey(), portView(view.level(), network, port.getKey(), port.getValue(), reach, now));
            }
        }

        NetworkSnapshot taken = new NetworkSnapshot(reach, read);
        seen = taken;
        standings.keySet().retainAll(present);
        publish(byLevel, taken);
        reconcileDesks(desks);
    }

    private void tend(List<ColonyView> views, long now) {
        synchronized (collecting) {
            collecting.values().removeIf(node -> node.expired(now));
        }
        for (ColonyView view : views) {
            for (CollectNode node : List.copyOf(collecting.values())) {
                if (node.deferred() && node.port().in(view.level())) {
                    node.retry(view.level());
                }
            }
        }
    }

    private void publish(Map<ServerLevel, List<WorldPos>> byLevel, NetworkSnapshot taken) {
        int reachTicks = CollectNode.reachTicks();
        Map<WorldPos, Stances> found = new LinkedHashMap<>();
        for (Map.Entry<ServerLevel, List<WorldPos>> level : byLevel.entrySet()) {
            List<Workshop> workshops = new ArrayList<>();
            for (WorldPos at : level.getValue()) {
                NetworkSnapshot.Port port = taken.ports().get(at);
                Standing standing = standings.get(at);
                if (standing == null) {
                    Optional<Stances> footings = Stances.of(level.getKey(),
                        Reach.workableCells(level.getKey(), at.block(level.getKey())));
                    if (footings.isEmpty()) {
                        continue;
                    }
                    standing = new Standing(UUID.randomUUID(), footings.get());
                    standings.put(at, standing);
                }
                if (port == null) {
                    continue;
                }
                if (port.parcels().stream().anyMatch(parcel -> StrayNode.stray(collecting, at, parcel))) {
                    found.put(at, standing.stances());
                }
                workshops.add(new Pickup(standing.id(), at, standing.stances(), collecting, eta, port,
                    taken.reachOf(port), reachTicks));
            }
            colony.publish(DispatchContent.ID, level.getKey().dimension(), workshops);
        }
        strays = Map.copyOf(found);
    }

    private void reconcileDesks(Map<WorldPos, List<Post>> desks) {
        for (Map.Entry<WorldPos, List<Post>> desk : desks.entrySet()) {
            keeping.computeIfAbsent(desk.getKey(), at -> new KeeperPosts(sweep::nudge))
                .reconcile(desk.getValue());
        }
        keeping.keySet().retainAll(desks.keySet());
    }

    public List<Grown> goals(ColonyView view) {
        List<Grown> goals = new ArrayList<>();
        for (KeeperPosts posts : keeping.values()) {
            posts.goals(view.level(), goals);
        }
        int reachTicks = CollectNode.reachTicks();
        strays.forEach((at, stances) -> {
            if (at.in(view.level())) {
                goals.add(Grown.of(new StrayNode(at, stances, reachTicks, collecting)).ranked(StrayNode.RANK));
            }
        });
        return goals;
    }

    private Map<WorldPos, List<Post>> desks(List<ColonyView> views, PackageNetwork network,
            Set<UUID> manned, Map<UUID, UUID> seated) {
        Map<WorldPos, List<Post>> found = new LinkedHashMap<>();
        for (ColonyView view : views) {
            ServerLevel level = view.level();
            Set<UUID> ours = new LinkedHashSet<>();
            for (Resident resident : view.residents()) {
                ours.add(resident.id());
            }
            for (UUID id : networks.all()) {
                for (BlockPos desk : network.desksOf(level, id)) {
                    WorldPos where = WorldPos.of(level, desk);
                    List<Post> empty = found.computeIfAbsent(where, at -> new ArrayList<>());
                    for (BlockPos seat : network.keeperSeats(level, desk)) {
                        Optional<UUID> sitting = network.sitting(level, seat);
                        if (sitting.isPresent()) {
                            if (ours.contains(sitting.get())) {
                                manned.add(id);
                                seated.put(sitting.get(), id);
                            }
                            continue;
                        }
                        Stances.of(level, Reach.workableCells(level, seat)).ifPresent(cells ->
                            empty.add(new Post(id, where, WorldPos.of(level, seat), cells)));
                    }
                }
            }
        }
        return found;
    }

    // Every network taken on brings all the ports along its chains with it, each port with the networks that reach it.
    private Map<WorldPos, Set<UUID>> portsIn(ColonyView view, PackageNetwork network) {
        ServerLevel level = view.level();
        Map<WorldPos, Set<UUID>> found = new LinkedHashMap<>();
        for (UUID id : networks.all()) {
            for (BlockPos pos : network.portsOf(level, id)) {
                if (level.isLoaded(pos) && network.isPort(level.getBlockState(pos))) {
                    found.computeIfAbsent(WorldPos.of(level, pos), at -> new LinkedHashSet<>()).add(id);
                }
            }
        }
        return found;
    }

    @Override
    public Optional<Attempt> pointedAt(BlockPos at, ColonyView view, ServerPlayer by) {
        PackageNetwork network = PackageNetworks.get();
        if (!network.partOfNetwork(view.level(), at)) {
            return Optional.empty();
        }
        return Optional.of(NetworkHandover.toggle(by, view.level(), this, network.networkAt(view.level(), at)));
    }

    // The port is sent to from whichever manned network that reaches it lands an order there soonest, save a route shut
    // after an order of its went missing.
    private NetworkSnapshot.Port portView(ServerLevel level, PackageNetwork network, WorldPos at, Set<UUID> reaching,
                                          Map<UUID, NetworkSnapshot.Reach> reach, long now) {
        List<PackageNetwork.Parcel> parcels = network.parcelsAt(level, at.block(level));
        Optional<UUID> from = Optional.empty();
        int best = Integer.MAX_VALUE;
        for (Map.Entry<UUID, NetworkSnapshot.Reach> entry : reach.entrySet()) {
            if (!entry.getValue().manned() || !reaching.contains(entry.getKey())
                || eta.shut(at, entry.getKey(), now)) {
                continue;
            }
            int guess = eta.estimate(at, entry.getKey(),
                network.transitTicks(level, entry.getKey(), at.block(level)));
            if (guess < best) {
                best = guess;
                from = Optional.of(entry.getKey());
            }
        }
        return new NetworkSnapshot.Port(network.addressOf(level, at.block(level)),
            network.routeOf(level, at.block(level)), network.portOpen(level, at.block(level)), parcels, from,
            from.isEmpty() ? Integer.MAX_VALUE : best);
    }

    // A keeper at the desk shows the latest order on its way through that network, as a walk shows where it is going.
    @Override
    public List<Line> doingLines(UUID resident, ColonyView view) {
        UUID network = keepers.get(resident);
        if (network == null) {
            return List.of();
        }
        return collecting.values().stream()
            .filter(CollectNode::waiting)
            .filter(request -> request.network().filter(network::equals).isPresent())
            .max(Comparator.comparingLong(CollectNode::placedAt))
            .flatMap(this::sending)
            .map(List::of)
            .orElseGet(List::of);
    }

    // The goods and how many, an arrow, and the pick-up point they are bound for; in words where the goods have no item.
    private Optional<Line> sending(CollectNode request) {
        Optional<ResourceLocation> item = Doings.item(request.goods());
        if (item.isPresent()) {
            return Optional.of(Line.told(Sentence.of(
                Sentence.glyph(DISPATCHING, new Notice("folkways.vocation.folkways.dispatching", List.of())),
                Sentence.ware(item.get(), request.count()), Sentence.YIELDS).then(bound(request.port()))));
        }
        return Doings.about(request.goods()).map(goods -> Line.of(DispatchNotice.SENDING,
            Notice.count(request.count()), Notice.named(goods)));
    }

    private Sentence bound(WorldPos at) {
        NetworkSnapshot.Port port = seen.ports().get(at);
        return port == null || port.address().isBlank()
            ? Sentence.of(Sentence.glyph(PORT))
            : Sentence.of(Sentence.glyph(PORT), word(port.address()));
    }

    private static Sentence.Token word(String text) {
        return Sentence.word(Notice.text(text));
    }

    public void closed(Closing why) {
        keeping.clear();
        keepers = Map.of();
        standings.clear();
        strays = Map.of();
        seen = NetworkSnapshot.empty();
        if (why == Closing.RAZED) {
            networks.clear();
            collecting.clear();
            remember();
        }
    }

    private void remember() {
        colony.keep(DispatchContent.ID, Writer.of()
            .blob(TAG_NETWORKS, networks.save())
            .tag());
    }

    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(DispatchContent.PAGE.id())) {
            return Optional.empty();
        }
        NetworkSnapshot taken = seen;
        long now = view.level().getGameTime();
        List<Board.Row> rows = new ArrayList<>();
        Map<UUID, Long> totals = taken.totals();
        if (!networks.all().isEmpty()) {
            rows.add(Board.Row.heading(Component.translatable("folkways.page.dispatch.networks")));
        }
        for (UUID id : networks.all()) {
            NetworkSnapshot.Reach reach = taken.networks().get(id);
            rows.add(new Board.Row(new ItemStack(Items.CHEST_MINECART),
                Component.literal(shortId(id)),
                reach != null && reach.flaw().isPresent()
                    ? Component.translatable("folkways.page.dispatch.paused",
                        Component.translatable(reach.flaw().get().kind().translationKey(),
                            reach.flaw().get().at().toShortString()))
                    : Component.translatable(reach != null && reach.manned()
                        ? "folkways.page.dispatch.manned" : "folkways.page.dispatch.unmanned",
                        totals.getOrDefault(id, 0L)),
                List.of()).told(networkTold(reach, totals.getOrDefault(id, 0L))));
        }
        if (!taken.ports().isEmpty()) {
            rows.add(Board.Row.heading(Component.translatable("folkways.page.dispatch.ports")));
        }
        for (Map.Entry<WorldPos, NetworkSnapshot.Port> entry : taken.ports().entrySet()) {
            NetworkSnapshot.Port port = entry.getValue();
            rows.add(new Board.Row(new ItemStack(Items.BARREL),
                Component.literal(port.address().isBlank() ? entry.getKey().cell().toShortString() : port.address()),
                Component.translatable("folkways.page.dispatch.port", port.parcels().size()),
                List.of(new Board.Act.Ping(entry.getKey().cell()))).told(port.parcels().isEmpty()
                    ? Sentence.of(Sentence.glyph(PORT), Sentence.glyph("lacks"), Sentence.ware(PARCEL, 0))
                    : Sentence.of(Sentence.glyph(PORT), Sentence.ware(PARCEL, port.parcels().size()))));
        }
        List<CollectNode> sent = collecting.values().stream().filter(CollectNode::waiting).toList();
        if (!sent.isEmpty()) {
            rows.add(Board.Row.heading(Component.translatable("folkways.page.dispatch.sent")));
        }
        for (CollectNode request : sent) {
            Optional<ResourceLocation> item = Doings.item(request.goods());
            long ago = (now - request.placedAt()) / 20L;
            rows.add(new Board.Row(item.map(id -> new ItemStack(BuiltInRegistries.ITEM.get(id)))
                    .filter(stack -> !stack.isEmpty()).orElseGet(() -> new ItemStack(Items.PAPER)),
                Doings.about(request.goods()).<Component>map(Component::translatable)
                    .orElseGet(() -> Component.literal(request.goods().describe())),
                Component.translatable("folkways.page.dispatch.ordered", request.count(),
                    ago, request.port().cell().toShortString()),
                List.of(new Board.Act.Ping(request.port().cell())))
                .told(item.map(id -> Sentence.of(Sentence.ware(id, request.count()), Sentence.YIELDS)
                    .then(bound(request.port()))
                    .then(Sentence.glyph("remaining"), Sentence.word(new Notice("folkways.page.dispatch.ago",
                        List.of(Notice.count(ago))))))
                    .orElse(Sentence.EMPTY)));
        }
        return Optional.of(new Board(
            List.of(new Board.Figure("folkways.page.dispatch.networks",
                Component.literal(Integer.toString(networks.all().size())))),
            rows,
            Optional.of(Component.translatable("folkways.page.dispatch.empty"))));
    }

    // Who is at the desk and what is in stock, or what the network lacks while it orders nothing.
    private static Sentence networkTold(NetworkSnapshot.Reach reach, long stock) {
        if (reach != null && reach.flaw().isPresent()) {
            return reach.flaw().get().kind().lacking()
                .map(block -> Sentence.of(Sentence.glyph("lacks"), Sentence.ware(block, 0)))
                .orElse(Sentence.EMPTY);
        }
        Sentence counted = Sentence.of(Sentence.doing(DispatchContent.KEEPING_POST), Sentence.glyph("stock"),
            word(Long.toString(stock)));
        return reach != null && reach.manned() ? counted : Sentence.of(Sentence.glyph("lacks")).then(counted);
    }

    private static String shortId(UUID network) {
        return network.toString().substring(0, 8);
    }
}
