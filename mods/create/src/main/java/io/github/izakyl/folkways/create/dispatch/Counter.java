package io.github.izakyl.folkways.create.dispatch;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.transport.BatchRequest;
import io.github.izakyl.folkways.core.api.transport.LayResult;
import io.github.izakyl.folkways.core.api.transport.QuoteResult;
import io.github.izakyl.folkways.core.api.transport.TransportEndpoint;
import io.github.izakyl.folkways.core.api.transport.TransportLaying;
import io.github.izakyl.folkways.core.api.transport.TransportLink;
import io.github.izakyl.folkways.core.api.transport.TransportMode;
import io.github.izakyl.folkways.core.api.transport.TransportQuery;
import io.github.izakyl.folkways.core.api.transport.TransportQuote;
import io.github.izakyl.folkways.core.api.transport.TransportSnapshot;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workshop;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;

/**
 * The colony's own post, as a way of moving goods: a resident hands a batch to a packager no network packs at, packs
 * it there by hand and sends it from the port beside it, along the chain or by train, to a port it reaches, where
 * another resident collects it. Each packager and port it reaches is one line. Taking from what a network already
 * holds is not this: that is the network's own supply, offered by its depot.
 */
record Counter(UUID key, WorkSite site, List<Desk> desks, Map<UUID, CollectNode> collecting, Eta eta,
               PackNode.Posting posting, int reachTicks) implements TransportMode {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(DispatchContent.ID.getNamespace(),
        "hand_post");
    private static final ResourceLocation PARCELS = ResourceLocation.fromNamespaceAndPath(
        DispatchContent.ID.getNamespace(), "parcels");

    // A packager to pack at, the port it sends from, and every pick-up point that port reaches.
    record Desk(WorldPos packager, Stances stances, WorldPos port, List<Depot.Dock> reaches) {

        Desk {
            reaches = List.copyOf(reaches);
        }

        UUID route() {
            return UUID.nameUUIDFromBytes(("folkways:dispatch/hand/" + port).getBytes(StandardCharsets.UTF_8));
        }
    }

    private record Line(Desk desk, Depot.Dock dock) {
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    // Every port a desk reaches that takes parcels now is a line of its own: the best one depends on where the goods
    // come from as much as on where they go, so none is left out for being farther from either.
    @Override
    public List<TransportLink> links(TransportQuery query, TransportSnapshot snapshot) {
        if ((long) Depot.PARCEL_SLOTS * Goods.stackSize(query.batch().goods()) <= 0) {
            return List.of();
        }
        List<TransportLink> links = new ArrayList<>();
        for (Desk desk : desks) {
            for (Depot.Dock dock : desk.reaches()) {
                if (desk.packager().sameRealm(query.from().at()) && dock.at().sameRealm(query.to().at())
                        && dock.sends() && Depot.PORT_SLOTS > dock.port().parcels().size()) {
                    links.add(link(desk, dock, snapshot.version()));
                }
            }
        }
        return List.copyOf(links);
    }

    // One parcel at a time: packing and collecting it are work, the way along only delay.
    @Override
    public QuoteResult quote(TransportLink link, BatchRequest batch, TransportSnapshot snapshot) {
        Optional<Line> line = line(link);
        int stack = Goods.stackSize(batch.goods());
        if (line.isEmpty() || stack <= 0) {
            return QuoteResult.NO;
        }
        long covered = Math.min(batch.count(), (long) Depot.PARCEL_SLOTS * stack);
        long cost = 2L * reachTicks + TransportQuote.delay(line.get().dock().eta());
        long handed = batch.readyAt() + (long) reachTicks + estimate(line.get().dock());
        return new QuoteResult.Available(new TransportQuote(ID, link, batch, covered, cost, handed,
            List.of(new TransportQuote.Load(covered, 1, batch.readyAt(), handed)),
            List.of(new TransportQuote.Occupancy(port(line.get().dock()), PARCELS, 1)),
            Map.of(ID, snapshot.version()), Optional.empty()), true);
    }

    // The goods are fed to the packing, which sends them before they are collected, whole, from the far port.
    @Override
    public LayResult lay(TransportQuote quote, TransportLaying plan) {
        Optional<Line> line = line(quote.link());
        if (line.isEmpty()) {
            return LayResult.UNAVAILABLE;
        }
        Desk desk = line.get().desk();
        Depot.Dock dock = line.get().dock();
        ItemSpec spec = quote.batch().goods();
        CollectNode collect = CollectNode.sentByHand(dock.at(), dock.stances(), spec, quote.covered(), reachTicks,
            estimate(dock), desk.route(), dock.port().route(), eta, collecting, order());
        PackNode pack = new PackNode(desk.packager(), desk.stances(), spec, quote.covered(), reachTicks, desk.port(),
            dock.port().route(), collect, posting);
        plan.add(pack);
        plan.add(collect);
        int free = Depot.PORT_SLOTS - dock.port().parcels().size();
        if (!plan.before(pack.id(), collect.id()) || !plan.reserve(port(dock), PARCELS, pack.id(), 1, free)
                || !plan.hold(new Waited(collecting, collect, Optional.empty())) || !plan.feed(pack.id())
                || !plan.output(collect.id())) {
            return LayResult.UNAVAILABLE;
        }
        return LayResult.LAID;
    }

    // Parcels sent by hand whose collection was dropped, offered to whatever else wants just those goods. They are
    // already on their way, so taking them over moves nothing that was not moving anyway.
    Workshop adrift() {
        return new Adrift(key, site, desks, collecting, eta, reachTicks);
    }

    private Optional<Line> line(TransportLink link) {
        for (Desk desk : desks) {
            for (Depot.Dock dock : desk.reaches()) {
                if (lineId(desk, dock).equals(link.id())) {
                    return Optional.of(new Line(desk, dock));
                }
            }
        }
        return Optional.empty();
    }

    private TransportLink link(Desk desk, Depot.Dock dock, long version) {
        return new TransportLink(lineId(desk, dock), version,
            endpoint("packager", desk.packager(), desk.stances()), endpoint("port", dock.at(), dock.stances()),
            List.of(), new TransportLink.Capacity(PARCELS, 1, Depot.PORT_SLOTS - dock.port().parcels().size(), 1, true),
            new TransportLink.Service(lineId(desk, dock), version, Long.MIN_VALUE, Long.MAX_VALUE),
            true, true, false);
    }

    // Packager and port both work on what is handed to them, so neither is a container: goods change hands there.
    private static TransportEndpoint endpoint(String kind, WorldPos at, Stances stances) {
        ResourceLocation id = name(kind, at.toString());
        Set<Stand> stands = stances instanceof Stances.Cells cells
            ? cells.cells().stream().map(Stand::new).collect(Collectors.toSet()) : Set.of(new Stand(at));
        return new TransportEndpoint(id, at, stands,
            new TransportEndpoint.Handover(TransportEndpoint.Form.PACK, id));
    }

    private static ResourceLocation lineId(Desk desk, Depot.Dock dock) {
        return name("line", desk.packager() + ">" + desk.port() + ">" + dock.at());
    }

    private static ResourceLocation port(Depot.Dock dock) {
        return name("port", dock.at().toString());
    }

    private static ResourceLocation name(String kind, String of) {
        return ResourceLocation.fromNamespaceAndPath(ID.getNamespace(), ID.getPath() + "/" + kind + "/"
            + UUID.nameUUIDFromBytes(of.getBytes(StandardCharsets.UTF_8)));
    }

    private int estimate(Depot.Dock dock) {
        return (int) Math.min(Integer.MAX_VALUE, (long) reachTicks + dock.eta());
    }

    // The colony's own order numbers run below Create's, which count up from nought.
    private static int order() {
        return ThreadLocalRandom.current().nextInt(Integer.MIN_VALUE, -1);
    }

    private record Adrift(UUID key, WorkSite site, List<Desk> desks, Map<UUID, CollectNode> collecting, Eta eta,
                          int reachTicks) implements Workshop {

        @Override
        public ResourceLocation id() {
            return DispatchContent.ID;
        }

        @Override
        public boolean alike() {
            return true;
        }

        @Override
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce produce) || Goods.stackSize(produce.wanted()) <= 0) {
                return List.of();
            }
            synchronized (collecting) {
                Optional<CollectNode> adrift = collecting.values().stream()
                    .filter(node -> node.byHand() && node.adrift() && Goods.includes(produce.wanted(), node.goods())
                        && produce.batching().runs(site, List.of(), node.count(), Optional.empty(), 1) >= 1)
                    .min(Comparator.comparingLong(CollectNode::placedAt));
                if (adrift.isEmpty()) {
                    return List.of();
                }
                CollectNode sent = adrift.get();
                Desk desk = desks.stream().filter(one -> sent.network().filter(one.route()::equals).isPresent())
                    .findFirst().orElse(null);
                Depot.Dock dock = desk == null ? null : desk.reaches().stream()
                    .filter(one -> one.at().equals(sent.port())).findFirst().orElse(null);
                if (dock == null) {
                    return List.of();
                }
                CollectNode node = CollectNode.sentByHand(dock.at(), dock.stances(), produce.wanted(), sent.count(),
                    reachTicks, (int) Math.min(Integer.MAX_VALUE, (long) reachTicks + dock.eta()), desk.route(),
                    dock.port().route(), eta, collecting, sent.handOrder()).adopting(sent);
                return List.of(Refinement.Change.of(Grown.of(node))
                    .holding(new Waited(collecting, node, adrift)));
            }
        }
    }

    // The collection waits on the colony's books for its parcel: dropped once the parcel is sent, it is left adrift
    // for another collection to adopt.
    static final class Waited implements Hold {

        private final Map<UUID, CollectNode> collecting;
        private final CollectNode node;
        private final Optional<CollectNode> adopted;

        Waited(Map<UUID, CollectNode> collecting, CollectNode node, Optional<CollectNode> adopted) {
            this.collecting = collecting;
            this.node = node;
            this.adopted = adopted;
        }

        @Override
        public Optional<RefusalKind> take() {
            synchronized (collecting) {
                if (adopted.isPresent()) {
                    CollectNode sent = adopted.get();
                    if (collecting.get(sent.id()) != sent || !sent.adrift()) {
                        return Optional.of(DispatchRefusal.NOTHING_ARRIVED);
                    }
                    collecting.remove(sent.id());
                }
                collecting.put(node.id(), node);
                return Optional.empty();
            }
        }

        @Override
        public void release(Ending how) {
            synchronized (collecting) {
                if (node.placed() && collecting.get(node.id()) == node) {
                    node.setAdrift();
                } else {
                    collecting.remove(node.id());
                }
            }
        }
    }
}
