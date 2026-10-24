package io.github.izakyl.folkways.create.dispatch;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.notice.Attempt;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import io.github.izakyl.folkways.front.api.panel.Board;
import io.github.izakyl.folkways.create.rail.domain.RailContent;
import io.github.izakyl.folkways.create.rail.domain.RailPresence;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

public final class DispatchPresence implements Facing {

    private static final String TAG_NETWORKS = "networks";
    private static final String TAG_MAIL = "mail";
    private static final String TAG_CHAINS = "chains";

    private static final ResourceLocation DISPATCHING =
        ResourceLocation.fromNamespaceAndPath("folkways", "vocation/dispatching");
    private static final ResourceLocation PORT = ResourceLocation.fromNamespaceAndPath("folkways", "delegation/port");
    private static final ResourceLocation PARCEL =
        ResourceLocation.fromNamespaceAndPath("create", "cardboard_package_12x12");
    private static final ResourceLocation CHAIN = ResourceLocation.fromNamespaceAndPath("create", "chain_conveyor");
    private static final ResourceLocation POSTBOX = ResourceLocation.fromNamespaceAndPath("create", "white_postbox");

    private final Colony colony;

    private final Sweep sweep;

    private final Networks networks = new Networks();
    private final ChainNets chains = new ChainNets();
    private final Map<UUID, CollectNode> collecting = new ConcurrentHashMap<>();
    private final Map<UUID, TakeNode> taking = new ConcurrentHashMap<>();
    private final Mailroom mail = new Mailroom();
    private final Eta eta = new Eta();

    private final Map<WorldPos, KeeperPosts> keeping = new LinkedHashMap<>();

    // Where a resident stands to reach each port and container, found once for as long as the block is there.
    private final Map<WorldPos, Stances> standings = new LinkedHashMap<>();

    private final Set<ResourceKey<Level>> withholding = new LinkedHashSet<>();

    private volatile NetworkSnapshot seen = NetworkSnapshot.empty();

    // A chain net given to the colony, by one of its chains, as the page lists it.
    private record ChainRow(WorldPos at, int frogports, int chains) {
    }

    // A postbox given to the colony: the station it is set to, and what waits in it for which train.
    private record PostRow(WorldPos at, String address, Optional<String> station, int parcels,
                           List<Optional<Component>> waiting) {
    }

    private volatile List<ChainRow> chainRows = List.of();

    private volatile List<PostRow> postRows = List.of();

    private volatile Map<WorldPos, Stances> strays = Map.of();

    // Which network each of our seated keepers is at the desk of.
    private volatile Map<UUID, UUID> keepers = Map.of();

    DispatchPresence(Colony colony) {
        this.colony = colony;
        this.sweep = new Sweep(colony, DispatchContent.ID, 20);
        Reader kept = Reader.of(colony.kept(DispatchContent.ID));
        kept.child(TAG_NETWORKS).ifPresent(networks::load);
        kept.child(TAG_MAIL).ifPresent(mail::load);
        kept.child(TAG_CHAINS).ifPresent(chains::load);
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

    // What keeps a network taken on from ordering, as last read: the flaw's key, or blank.
    public String flawOf(UUID network) {
        NetworkSnapshot.Reach reach = seen.networks().get(network);
        return reach == null ? "" : reach.flaw().map(flaw -> flaw.kind().translationKey()).orElse("");
    }

    // Takes on the whole chain net this chain conveyor is joined to.
    public boolean takeChain(ServerLevel level, BlockPos chain) {
        if (!chains.take(level, PackageNetworks.get().chainNet(level, chain))) {
            return false;
        }
        remember();
        return true;
    }

    public boolean releaseChain(ServerLevel level, BlockPos chain) {
        if (!chains.drop(level, PackageNetworks.get().chainNet(level, chain))) {
            return false;
        }
        remember();
        return true;
    }

    public boolean holdsChain(ServerLevel level, BlockPos chain) {
        return chains.holdsAny(level, PackageNetworks.get().chainNet(level, chain));
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
        rewalk(views, network);
        tend(views, now);
        post(views, network);
        readPosts(server, views, network);
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
            for (ColonyView view : views) {
                flaw = flaw.or(() -> uncarried(view.level(), network, id));
            }
            reach.put(id, new NetworkSnapshot.Reach(network.summaryOf(server, id), manned.contains(id), flaw));
        }

        Set<WorldPos> present = new LinkedHashSet<>();
        Map<WorldPos, NetworkSnapshot.Port> read = new LinkedHashMap<>();
        Map<ServerLevel, Map<WorldPos, Set<UUID>>> byLevel = new LinkedHashMap<>();
        Map<ServerLevel, Map<UUID, List<WorldPos>>> stocked = new LinkedHashMap<>();
        for (ColonyView view : views) {
            Map<WorldPos, Set<UUID>> found = portsIn(view, network);
            byLevel.put(view.level(), found);
            for (Map.Entry<WorldPos, Set<UUID>> port : found.entrySet()) {
                present.add(port.getKey());
                read.put(port.getKey(), portView(view.level(), network, port.getKey(), port.getValue(), reach, now));
            }
            Map<UUID, List<WorldPos>> bins = containersIn(view, network);
            stocked.put(view.level(), bins);
            bins.values().forEach(present::addAll);
            for (WorldPos port : given(view.level(), network)) {
                present.add(port);
                network.packerAt(view.level(), port.block(view.level()))
                    .ifPresent(packer -> present.add(WorldPos.of(view.level(), packer)));
            }
        }

        NetworkSnapshot taken = new NetworkSnapshot(reach, read);
        seen = taken;
        standings.keySet().retainAll(present);
        publish(network, byLevel, stocked, taken, now);
        reconcileDesks(desks);
    }

    // Each chain net given to the colony is walked again from what is kept of it, so chains added to it are taken on
    // and chains broken off it are let go.
    private void rewalk(List<ColonyView> views, PackageNetwork network) {
        boolean moved = false;
        List<ChainRow> rows = new ArrayList<>();
        for (ColonyView view : views) {
            ServerLevel level = view.level();
            Set<BlockPos> loaded = new LinkedHashSet<>();
            Set<BlockPos> walked = new LinkedHashSet<>();
            for (BlockPos chain : chains.in(level)) {
                if (!level.isLoaded(chain)) {
                    continue;
                }
                loaded.add(chain);
                if (!walked.contains(chain)) {
                    Set<BlockPos> net = network.chainNet(level, chain);
                    walked.addAll(net);
                    if (!net.isEmpty()) {
                        rows.add(new ChainRow(WorldPos.of(level, chain), network.frogportsOn(level, net).size(),
                            net.size()));
                    }
                }
            }
            moved |= chains.rewalked(level, loaded, walked);
        }
        chainRows = List.copyOf(rows);
        if (moved) {
            remember();
        }
    }

    // Each postbox given to the colony, and the train each parcel of the colony's in it waits for.
    private void readPosts(MinecraftServer server, List<ColonyView> views, PackageNetwork network) {
        List<PostRow> rows = new ArrayList<>();
        for (ColonyView view : views) {
            ServerLevel level = view.level();
            for (WorldPos at : posts(level)) {
                BlockPos post = at.block(level);
                List<Optional<Component>> waiting = new ArrayList<>();
                for (Mailroom.Consignment held : mail.in(level)) {
                    if (held.from().equals(at)) {
                        waiting.add(held.train().flatMap(train -> network.trainName(server, train)));
                    }
                }
                rows.add(new PostRow(at, network.addressOf(level, post), network.stationOf(level, post),
                    network.parcelsAt(level, post).size(), waiting));
            }
        }
        postRows = List.copyOf(rows);
    }

    // A network's parcels go only by what was given to the colony: the chain net its frogports throw onto, or the
    // postbox beside each of its packagers. Without that it orders nothing, though its containers are still there.
    private Optional<PackageNetwork.Flaw> uncarried(ServerLevel level, PackageNetwork network, UUID id) {
        Set<WorldPos> posted = posts(level);
        for (BlockPos sender : network.sendersOf(level, id)) {
            if (!carries(level, network, sender, posted)) {
                return Optional.of(new PackageNetwork.Flaw(NetworkFlaw.NOT_CARRIED, sender));
            }
        }
        return Optional.empty();
    }

    // Whether a port was given to the colony: a frogport on a chain net it holds, or a postbox it holds.
    private boolean carries(ServerLevel level, PackageNetwork network, BlockPos port, Set<WorldPos> posted) {
        if (network.frogport(level, port)) {
            return network.chainAt(level, port).filter(chain -> chains.holds(WorldPos.of(level, chain))).isPresent();
        }
        return posted.contains(WorldPos.of(level, port));
    }

    // A parcel goes by train only aboard a train the colony took on, which its own drivers run.
    private boolean drives(UUID train) {
        return colony.service(RailContent.ID, Object.class).filter(RailPresence.class::isInstance)
            .map(rail -> ((RailPresence) rail).holds(train)).orElse(false);
    }

    // Takes whose goods are out of their containers are let go before the stock is read again, which no longer holds
    // them.
    private void tend(List<ColonyView> views, long now) {
        synchronized (collecting) {
            collecting.values().removeIf(node -> node.expired(now));
            taking.values().removeIf(node -> node.takenAt() >= 0);
        }
        for (ColonyView view : views) {
            for (CollectNode node : List.copyOf(collecting.values())) {
                if (node.deferred() && node.port().in(view.level())) {
                    node.retry(view.level());
                }
            }
        }
    }

    // A parcel a network that mails by train packed for one of the colony's orders is handed from its packager into
    // the postbox beside it and waits there, as does one a resident sent by hand: the colony books each onto the
    // train of its own that brings it soonest to its order's postbox, and books it again as the trains run, and
    // Create lets it aboard that train alone. One no train will carry, once its order is let go, goes back to the
    // network; one gone from its postbox is off the books.
    private void post(List<ColonyView> views, PackageNetwork network) {
        boolean moved = false;
        for (ColonyView view : views) {
            ServerLevel level = view.level();
            for (UUID id : networks.all()) {
                network.post(level, id, order -> orderOf(id, order).isPresent());
                for (BlockPos postbox : network.postsOf(level, id)) {
                    WorldPos from = WorldPos.of(level, postbox);
                    for (PackageNetwork.Parcel parcel : network.parcelsAt(level, postbox)) {
                        if (parcel.order().isEmpty() || mail.holds(from, parcel.order().getAsInt())) {
                            continue;
                        }
                        Optional<CollectNode> order = orderOf(id, parcel.order().getAsInt());
                        if (order.isPresent()) {
                            mail.take(new Mailroom.Consignment(UUID.randomUUID(), parcel.order().getAsInt(), id,
                                from, order.get().port(), Optional.empty(), ""));
                            moved = true;
                        }
                    }
                }
            }
            for (Mailroom.Consignment held : mail.in(level)) {
                BlockPos from = held.from().block(level);
                if (!level.isLoaded(from)) {
                    continue;
                }
                if (network.parcelsAt(level, from).stream()
                        .noneMatch(parcel -> parcel.order().equals(OptionalInt.of(held.order())))) {
                    moved |= mail.drop(held);
                    continue;
                }
                Optional<PackageNetwork.Route> route = held.to().equals(held.from()) ? Optional.empty()
                    : network.route(level, from, held.to().block(level), this::drives);
                moved |= mail.replace(held, held.booked(route));
                if (route.isEmpty() && orderOf(held.network(), held.order()).isEmpty()) {
                    moved |= network.giveBack(level, held.network(), from, held.order());
                }
            }
        }
        if (moved) {
            remember();
        }
    }

    // Whether a train may take a parcel of this order from a postbox at that station: only the train it is booked on,
    // and none while an order of the colony's still waits to be booked. Empty when the order is none of the colony's.
    Optional<Boolean> boards(int order, UUID train, String station) {
        List<Mailroom.Consignment> booked = mail.ofOrder(order);
        if (!booked.isEmpty()) {
            return Optional.of(booked.stream().anyMatch(one -> one.boards(train, station)));
        }
        return collecting.values().stream().anyMatch(node -> node.ordered(order)) ? Optional.of(false)
            : Optional.empty();
    }

    // The order still waiting on its parcels that the network packed under this number.
    private Optional<CollectNode> orderOf(UUID network, int order) {
        return collecting.values().stream()
            .filter(node -> node.ordered(order) && node.network().filter(network::equals).isPresent())
            .findFirst();
    }

    // One depot for each network in each level: its pick-up points, its containers, and the one tally behind them. The
    // containers are the network's own, kept out of the colony's stock, whoever offers them as stores.
    private void publish(PackageNetwork network, Map<ServerLevel, Map<WorldPos, Set<UUID>>> byLevel,
                         Map<ServerLevel, Map<UUID, List<WorldPos>>> stocked, NetworkSnapshot taken, long now) {
        int reachTicks = CollectNode.reachTicks();
        Map<WorldPos, Stances> found = new LinkedHashMap<>();
        Set<ResourceKey<Level>> withheld = new LinkedHashSet<>();
        for (Map.Entry<ServerLevel, Map<WorldPos, Set<UUID>>> here : byLevel.entrySet()) {
            ServerLevel level = here.getKey();
            Map<UUID, List<Depot.Dock>> docks = new LinkedHashMap<>();
            for (Map.Entry<WorldPos, Set<UUID>> entry : here.getValue().entrySet()) {
                WorldPos at = entry.getKey();
                NetworkSnapshot.Port port = taken.ports().get(at);
                Optional<Stances> stances = stancesAt(level, at);
                if (stances.isEmpty() || port == null) {
                    continue;
                }
                if (port.parcels().stream().anyMatch(parcel -> StrayNode.stray(collecting, at, parcel))) {
                    found.put(at, stances.get());
                }
                for (UUID id : entry.getValue()) {
                    NetworkSnapshot.Reach reach = taken.networks().get(id);
                    boolean sends = reach != null && reach.flaw().isEmpty() && port.open() && port.addressable()
                        && !eta.shut(at, id, now);
                    docks.computeIfAbsent(id, key -> new ArrayList<>()).add(new Depot.Dock(at, stances.get(), port,
                        eta.estimate(at, id, network.transitTicks(level, id, at.block(level), this::drives)), sends));
                }
            }
            Map<UUID, List<WorldPos>> bins = stocked.getOrDefault(level, Map.of());
            List<WorldPos> held = new ArrayList<>();
            List<Workshop> workshops = new ArrayList<>();
            for (UUID id : networks.all()) {
                NetworkSnapshot.Reach reach = taken.networks().get(id);
                if (reach == null) {
                    continue;
                }
                List<Depot.Shelf> shelves = new ArrayList<>();
                for (WorldPos at : bins.getOrDefault(id, List.of())) {
                    held.add(at);
                    stancesAt(level, at).ifPresent(stances -> shelves.add(
                        new Depot.Shelf(at, stances, counted(network.heldIn(level, at.block(level))))));
                }
                List<Depot.Dock> ours = docks.getOrDefault(id, List.of());
                Optional<WorldPos> site = shelves.stream().map(Depot.Shelf::at).findFirst()
                    .or(() -> ours.stream().map(Depot.Dock::at).findFirst());
                if (site.isEmpty()) {
                    continue;
                }
                workshops.add(new Depot(depotKey(id, level), new WorkSite.AtBlock(site.get()),
                    new Tally(id, reach.supply(), now, reach.manned(), collecting, taking), ours, shelves, eta,
                    reachTicks));
            }
            counter(network, level, now, reachTicks, found).ifPresent(workshops::add);
            colony.publish(DispatchContent.ID, level.dimension(), workshops);
            colony.withhold(DispatchContent.ID, level.dimension(), held);
            withheld.add(level.dimension());
        }
        for (ResourceKey<Level> gone : withholding) {
            if (!withheld.contains(gone)) {
                colony.withhold(DispatchContent.ID, gone, List.of());
            }
        }
        withholding.clear();
        withholding.addAll(withheld);
        strays = Map.copyOf(found);
    }

    // The postboxes the colony was given in this level.
    private Set<WorldPos> posts(ServerLevel level) {
        Set<WorldPos> found = new LinkedHashSet<>();
        for (Holding holding : colony.holdings(DispatchContent.PORT)) {
            if (holding.what() instanceof Held.Block(WorldPos cell) && cell.in(level) && level.isLoaded(cell.block(level))
                    && PackageNetworks.get().isPostbox(level.getBlockState(cell.block(level)))) {
                found.add(cell);
            }
        }
        return found;
    }

    // Every port the colony was given in this level: the frogports on its chain nets, and its postboxes.
    private List<WorldPos> given(ServerLevel level, PackageNetwork network) {
        List<WorldPos> found = new ArrayList<>();
        for (BlockPos frogport : network.frogportsOn(level, chains.in(level))) {
            found.add(WorldPos.of(level, frogport));
        }
        found.addAll(posts(level));
        return found;
    }

    // The colony's own post in this level: each port it was given that stands by a packager no network packs at
    // sends from there, by hand, to any other port it was given that the parcel would reach. A stray parcel at one
    // of them is tidied away as at any pick-up point.
    private Optional<Counter> counter(PackageNetwork network, ServerLevel level, long now, int reachTicks,
                                      Map<WorldPos, Stances> strays) {
        List<WorldPos> given = given(level, network);
        List<Counter.Desk> desks = new ArrayList<>();
        for (WorldPos port : given) {
            Optional<Stances> standing = stancesAt(level, port);
            if (standing.isPresent() && network.parcelsAt(level, port.block(level)).stream()
                    .anyMatch(parcel -> StrayNode.stray(collecting, port, parcel))) {
                strays.putIfAbsent(port, standing.get());
            }
            Optional<BlockPos> packer = network.packerAt(level, port.block(level));
            Optional<Stances> packing = packer.flatMap(at -> stancesAt(level, WorldPos.of(level, at)));
            if (packing.isEmpty()) {
                continue;
            }
            Counter.Desk desk = new Counter.Desk(WorldPos.of(level, packer.get()), packing.get(), port, List.of());
            List<Depot.Dock> reaches = new ArrayList<>();
            for (BlockPos to : network.reachedFrom(level, port.block(level), this::drives)) {
                WorldPos at = WorldPos.of(level, to);
                Optional<Stances> stances = stancesAt(level, at);
                if (!given.contains(at) || stances.isEmpty()) {
                    continue;
                }
                NetworkSnapshot.Port view = new NetworkSnapshot.Port(network.addressOf(level, to),
                    network.routeOf(level, to), network.portOpen(level, to), network.parcelsAt(level, to),
                    Optional.empty(), 0);
                int guess = eta.estimate(at, desk.route(),
                    network.handTicks(level, port.block(level), to, this::drives));
                reaches.add(new Depot.Dock(at, stances.get(), view, guess,
                    view.open() && view.addressable() && !eta.shut(at, desk.route(), now)));
            }
            if (!reaches.isEmpty()) {
                desks.add(new Counter.Desk(desk.packager(), desk.stances(), port, reaches));
            }
        }
        if (desks.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Counter(UUID.nameUUIDFromBytes(("folkways:dispatch/counter/"
                + level.dimension().location()).getBytes(StandardCharsets.UTF_8)),
            new WorkSite.AtBlock(desks.getFirst().packager()), desks, collecting, eta, this::send, reachTicks));
    }

    // A parcel packed by hand goes from its port: thrown onto the chain, or left in the postbox on the colony's books
    // for the train that brings it soonest.
    private boolean send(ServerLevel level, WorldPos from, WorldPos to, ItemStack parcel, CollectNode collect) {
        PackageNetwork network = PackageNetworks.get();
        if (!network.mails(level, from.block(level))) {
            return network.send(level, from.block(level), parcel);
        }
        Optional<PackageNetwork.Route> route = network.route(level, from.block(level), to.block(level), this::drives);
        if (route.isEmpty() || collect.network().isEmpty()) {
            return false;
        }
        Mailroom.Consignment held = new Mailroom.Consignment(UUID.randomUUID(), collect.handOrder(),
            collect.network().get(), from, to, Optional.empty(), "").booked(route);
        mail.take(held);
        if (!network.send(level, from.block(level), parcel)) {
            mail.drop(held);
            return false;
        }
        remember();
        return true;
    }

    private Optional<Stances> stancesAt(ServerLevel level, WorldPos at) {
        Stances known = standings.get(at);
        if (known != null) {
            return Optional.of(known);
        }
        Optional<Stances> footings = Stances.of(level, Reach.workableCells(level, at.block(level)));
        footings.ifPresent(stances -> standings.put(at, stances));
        return footings;
    }

    private static Map<ItemSpec, Long> counted(List<ItemStack> stacks) {
        Map<ItemSpec, Long> held = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            held.merge(Goods.specOf(stack), (long) stack.getCount(), Long::sum);
        }
        return held;
    }

    private static UUID depotKey(UUID network, ServerLevel level) {
        return UUID.nameUUIDFromBytes(("folkways:dispatch/depot/" + network + "/" + level.dimension().location())
            .getBytes(StandardCharsets.UTF_8));
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
                goals.add(Grown.of(new StrayNode(at, stances, reachTicks, collecting)));
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

    // The containers of every network taken on, by network.
    private Map<UUID, List<WorldPos>> containersIn(ColonyView view, PackageNetwork network) {
        ServerLevel level = view.level();
        Map<UUID, List<WorldPos>> found = new LinkedHashMap<>();
        for (UUID id : networks.all()) {
            for (BlockPos pos : network.containersOf(level, id)) {
                found.computeIfAbsent(id, key -> new ArrayList<>()).add(WorldPos.of(level, pos));
            }
        }
        return found;
    }

    // Every network taken on reaches the ports along its chains, or its trains' postboxes, that were given to the
    // colony: each port with the networks that reach it.
    private Map<WorldPos, Set<UUID>> portsIn(ColonyView view, PackageNetwork network) {
        ServerLevel level = view.level();
        Set<WorldPos> posted = posts(level);
        Map<WorldPos, Set<UUID>> found = new LinkedHashMap<>();
        for (UUID id : networks.all()) {
            for (BlockPos pos : network.portsOf(level, id, this::drives)) {
                if (level.isLoaded(pos) && network.isPort(level.getBlockState(pos))
                        && carries(level, network, pos, posted)) {
                    found.computeIfAbsent(WorldPos.of(level, pos), at -> new LinkedHashSet<>()).add(id);
                }
            }
        }
        return found;
    }

    // A stock link or stock ticker hands over its network, a frogport or chain conveyor its chain net. A postbox is
    // given on its own, as a member.
    @Override
    public Optional<Attempt> pointedAt(BlockPos at, ColonyView view, ServerPlayer by) {
        PackageNetwork network = PackageNetworks.get();
        ServerLevel level = view.level();
        Optional<UUID> linked = network.linkerAt(level, at);
        if (linked.isPresent()) {
            return Optional.of(NetworkHandover.toggle(by, level, this, linked.get()));
        }
        Optional<BlockPos> chain = network.chainAt(level, at);
        if (chain.isPresent()) {
            return Optional.of(NetworkHandover.toggleChain(by, level, this, chain.get()));
        }
        if (network.frogport(level, at)) {
            return Optional.of(Attempt.refused(DispatchRefusal.OFF_CHAIN));
        }
        return Optional.empty();
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
                network.transitTicks(level, entry.getKey(), at.block(level), this::drives));
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
        chainRows = List.of();
        postRows = List.of();
        standings.clear();
        strays = Map.of();
        seen = NetworkSnapshot.empty();
        if (why == Closing.RAZED) {
            networks.clear();
            collecting.clear();
            taking.clear();
            mail.clear();
            chains.clear();
            remember();
        }
    }

    private void remember() {
        colony.keep(DispatchContent.ID, Writer.of()
            .blob(TAG_NETWORKS, networks.save())
            .blob(TAG_MAIL, mail.save())
            .blob(TAG_CHAINS, chains.save())
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
        List<ChainRow> nets = chainRows;
        if (!nets.isEmpty()) {
            rows.add(Board.Row.heading(Component.translatable("folkways.page.dispatch.chains")));
        }
        for (ChainRow net : nets) {
            rows.add(new Board.Row(new ItemStack(BuiltInRegistries.ITEM.get(CHAIN)),
                Component.literal(net.at().cell().toShortString()),
                Component.translatable("folkways.page.dispatch.chain", net.frogports(), net.chains()),
                List.of(new Board.Act.Ping(net.at().cell()))));
        }
        List<PostRow> boxes = postRows;
        if (!boxes.isEmpty()) {
            rows.add(Board.Row.heading(Component.translatable("folkways.page.dispatch.posts")));
        }
        for (PostRow box : boxes) {
            rows.add(new Board.Row(new ItemStack(BuiltInRegistries.ITEM.get(POSTBOX)),
                Component.literal(box.address().isBlank() ? box.at().cell().toShortString() : box.address()),
                postDetail(box), List.of(new Board.Act.Ping(box.at().cell()))));
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

    // The station a postbox sends from, and for each parcel of the colony's waiting in it the train booked to take
    // it, or that none of the colony's trains will.
    private static Component postDetail(PostRow box) {
        if (box.station().isEmpty()) {
            return Component.translatable("folkways.page.dispatch.post_unstationed", box.parcels());
        }
        for (Optional<Component> train : box.waiting()) {
            if (train.isEmpty()) {
                return Component.translatable("folkways.page.dispatch.post_unbooked", box.station().get());
            }
        }
        return box.waiting().isEmpty()
            ? Component.translatable("folkways.page.dispatch.post", box.station().get(), box.parcels())
            : Component.translatable("folkways.page.dispatch.post_booked", box.station().get(),
                box.waiting().getFirst().get());
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
