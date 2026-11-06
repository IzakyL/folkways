package io.github.izakyl.folkways.carts;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.Stand;
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
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Stances;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;

// The colony's cart lines as a way of moving goods: from any station a cart runs to any station it runs on to, put
// aboard while it stands at the one and taken off while it stands at the other. A line's cart runs to its timetable
// whether or not it carries anything, so a batch waits for the cart as for a train.
record CartTransport(CartLines lines, List<Running> running) implements TransportMode {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "cart_line");
    private static final ResourceLocation CELLS = ResourceLocation.fromNamespaceAndPath("folkways", "cart_cells");

    // A line whose cart stands ready, and how many of the cart's cells no batch has taken.
    record Running(Line line, UUID cart, int free) {
    }

    private record Leg(Running running, int from, int to) {
    }

    CartTransport {
        running = List.copyOf(running);
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public List<TransportLink> links(TransportQuery query, TransportSnapshot snapshot) {
        if (Goods.stackSize(query.batch().goods()) <= 0) {
            return List.of();
        }
        List<TransportLink> links = new ArrayList<>();
        for (Running one : running) {
            if (one.free() <= 0) {
                continue;
            }
            for (Line.Mark from : one.line().course()) {
                for (Line.Mark to : one.line().course()) {
                    if (from.isStation() && to.isStation() && from.at().sameRealm(query.from().at())
                            && to.at().sameRealm(query.to().at())
                            && one.line().ride(from.station(), to.station()) >= 0) {
                        links.add(link(one, from, to, snapshot.version()));
                    }
                }
            }
        }
        return List.copyOf(links);
    }

    // Waiting for the cart is reckoned at half a round of the line, as it may be anywhere on it; the ride itself
    // includes the stops on the way. Only putting the goods aboard and taking them off are anyone's work: the cart
    // runs anyway, so waiting and riding count as delay.
    @Override
    public QuoteResult quote(TransportLink link, BatchRequest batch, TransportSnapshot snapshot) {
        Optional<Leg> leg = leg(link);
        int stack = Goods.stackSize(batch.goods());
        if (leg.isEmpty() || stack <= 0) {
            return QuoteResult.NO;
        }
        Line line = leg.get().running().line();
        long covered = Math.min(batch.count(), (long) leg.get().running().free() * stack);
        if (covered <= 0) {
            return QuoteResult.NO;
        }
        long underway = line.cycle() / 2 + line.ride(leg.get().from(), leg.get().to());
        long handling = 2L * FolkwaysConfig.haulReachTicks();
        long cost = handling + TransportQuote.delay(underway);
        long handed = batch.readyAt() + handling + underway;
        return new QuoteResult.Available(new TransportQuote(ID, link, batch, covered, cost, handed,
            List.of(new TransportQuote.Load(covered, 1, batch.readyAt(), handed)),
            List.of(new TransportQuote.Occupancy(cells(leg.get().running()), CELLS,
                LineState.cells(batch.goods(), covered))),
            Map.of(ID, snapshot.version()), Optional.empty()), true);
    }

    @Override
    public LayResult lay(TransportQuote quote, TransportLaying plan) {
        Optional<Leg> leg = leg(quote.link());
        if (leg.isEmpty()) {
            return LayResult.UNAVAILABLE;
        }
        Line line = leg.get().running().line();
        Optional<LineState> state = lines.state(line.id())
            .filter(found -> found.cart.equals(Optional.of(leg.get().running().cart())));
        Optional<Line.Mark> from = line.station(leg.get().from());
        Optional<Line.Mark> to = line.station(leg.get().to());
        if (state.isEmpty() || from.isEmpty() || to.isEmpty()) {
            return LayResult.UNAVAILABLE;
        }
        LineState.Consignment sent = new LineState.Consignment(UUID.randomUUID(), quote.batch().goods(),
            quote.covered(), leg.get().from(), leg.get().to());
        LineWork.Load load = new LineWork.Load(state.get(), line, from.get(), sent);
        LineWork.Unload unload = new LineWork.Unload(state.get(), line, to.get(), sent);
        plan.add(load);
        plan.add(unload);
        if (!plan.before(load.id(), unload.id())
                || !plan.reserve(cells(leg.get().running()), CELLS, load.id(),
                    LineState.cells(sent.goods, sent.count), leg.get().running().free())
                || !plan.hold(new Booked(state.get(), sent)) || !plan.feed(load.id()) || !plan.output(unload.id())) {
            return LayResult.UNAVAILABLE;
        }
        return LayResult.LAID;
    }

    private Optional<Leg> leg(TransportLink link) {
        for (Running one : running) {
            for (Line.Mark from : one.line().course()) {
                for (Line.Mark to : one.line().course()) {
                    if (from.isStation() && to.isStation()
                            && lineId(one, from.station(), to.station()).equals(link.id())) {
                        return Optional.of(new Leg(one, from.station(), to.station()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static TransportLink link(Running one, Line.Mark from, Line.Mark to, long version) {
        ResourceLocation id = lineId(one, from.station(), to.station());
        return new TransportLink(id, version, endpoint(one, from), endpoint(one, to), List.of(),
            new TransportLink.Capacity(CELLS, one.free(), one.free(), 1, false),
            new TransportLink.Service(id, version, Long.MIN_VALUE, Long.MAX_VALUE), true, true, false);
    }

    // A station is reached through the cart standing at it: goods change hands there, it is no store.
    private static TransportEndpoint endpoint(Running one, Line.Mark mark) {
        ResourceLocation id = name("station/" + one.line().id() + "/" + mark.station());
        Set<Stand> stands = mark.stances() instanceof Stances.Cells cells
            ? cells.cells().stream().map(Stand::new).collect(Collectors.toSet()) : Set.of(new Stand(mark.at()));
        return new TransportEndpoint(id, mark.at(), stands,
            new TransportEndpoint.Handover(TransportEndpoint.Form.PACK, id));
    }

    private static ResourceLocation lineId(Running one, int from, int to) {
        return name("line/" + one.line().id() + "/" + one.cart() + "/" + from + "/" + to);
    }

    private static ResourceLocation cells(Running one) {
        return name("cart/" + one.cart());
    }

    private static ResourceLocation name(String of) {
        return ResourceLocation.fromNamespaceAndPath(ID.getNamespace(), ID.getPath() + "/"
            + UUID.nameUUIDFromBytes(of.getBytes(StandardCharsets.UTF_8)));
    }

    // The batch is booked on the line while its work stands. Dropped before it is aboard, it is unbooked; dropped
    // once aboard, its goods are no one's and are set down at the next station.
    private record Booked(LineState state, LineState.Consignment sent) implements Hold {

        @Override
        public Optional<RefusalKind> take() {
            synchronized (state.aboard) {
                state.aboard.put(sent.id, sent);
            }
            return Optional.empty();
        }

        @Override
        public void release(Ending how) {
            synchronized (state.aboard) {
                state.aboard.remove(sent.id, sent);
            }
        }
    }
}
