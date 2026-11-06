package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
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
import io.github.izakyl.folkways.core.api.transport.TransportTravel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

// An unregistered quote provider. Execution still belongs to Carry.
public final class DirectTransport implements TransportMode {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "direct");
    private static final ResourceLocation ITEMS = ResourceLocation.fromNamespaceAndPath("folkways", "items");

    // Called while capturing an already eligible carrier, outside quoting and estimating.
    public static TransportSnapshot.Carrier carrier(Resident who, WorldPos at, ItemSpec goods, long perTrip,
                                                     long reachTicks) {
        return new TransportSnapshot.Carrier(who, at, Map.of(goods, perTrip), reachTicks);
    }

    private record Route(ResourceLocation kind, WorldPos from, WorldPos to) {
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public List<TransportLink> links(TransportQuery query, TransportSnapshot snapshot) {
        List<TransportLink> links = new ArrayList<>();
        for (TransportEndpoint to : query.hubs()) {
            if (!query.from().equals(to)) {
                links.add(new TransportLink(ID, snapshot.version(), query.from(), to, List.of(query.batch().goods()),
                    new TransportLink.Capacity(ITEMS, Long.MAX_VALUE, Long.MAX_VALUE, 1, false),
                    new TransportLink.Service(ID, snapshot.version(), Long.MIN_VALUE, Long.MAX_VALUE),
                    true, true, to.handover().form() == TransportEndpoint.Form.INVENTORY));
            }
        }
        return List.copyOf(links);
    }

    @Override
    public QuoteResult quote(TransportLink link, BatchRequest batch, TransportSnapshot snapshot) {
        if (link.from().handover().form() == TransportEndpoint.Form.PARCEL
                || link.to().handover().form() == TransportEndpoint.Form.PARCEL) {
            return QuoteResult.NO;
        }
        Map<Route, TransportTravel.Result> legs = new HashMap<>();
        TransportQuote best = null;
        boolean pending = false;
        for (TransportSnapshot.Carrier carrier : snapshot.carriers()) {
            long room = carrier.capacity(batch.goods());
            if (room == 0) {
                continue;
            }
            long trips = (batch.count() - 1) / room + 1;
            for (var loading : link.from().stands()) {
                var approach = snapshot.travel().estimate(carrier.who().routeKind(), carrier.at(), loading.cell(),
                    batch.readyAt());
                for (var unloading : link.to().stands()) {
                    var loaded = legs.computeIfAbsent(
                        new Route(carrier.who().routeKind(), loading.cell(), unloading.cell()),
                        route -> snapshot.travel().estimate(route.kind(), route.from(), route.to(), batch.readyAt()));
                    TransportTravel.Result back = trips == 1 ? new TransportTravel.Yes(0, 0)
                        : legs.computeIfAbsent(new Route(carrier.who().routeKind(), unloading.cell(), loading.cell()),
                            route -> snapshot.travel().estimate(route.kind(), route.from(), route.to(),
                                batch.readyAt()));
                    if (approach instanceof TransportTravel.No || loaded instanceof TransportTravel.No
                            || back instanceof TransportTravel.No) {
                        continue;
                    }
                    if (!(approach instanceof TransportTravel.Yes in) || !(loaded instanceof TransportTravel.Yes out)
                            || !(back instanceof TransportTravel.Yes returning)) {
                        pending = true;
                        continue;
                    }
                    // Every connection uses the same departure snapshot: these are static costs, not a timetable.
                    long handling = Math.multiplyExact(carrier.reachTicks(),
                        (link.loading() ? 1L : 0L) + (link.unloading() ? 1L : 0L));
                    long cost = Math.addExact(in.ticks(), Math.addExact(
                        Math.multiplyExact(Math.addExact(out.ticks(), handling), trips),
                        Math.multiplyExact(returning.ticks(), trips - 1)));
                    if (best == null || cost < best.cost()) {
                        long handed = Math.addExact(batch.readyAt(), cost);
                        List<TransportQuote.Load> loads = new ArrayList<>();
                        long full = batch.count() / room;
                        if (full > 0) {
                            loads.add(new TransportQuote.Load(room, full, batch.readyAt(), handed));
                        }
                        long rest = batch.count() % room;
                        if (rest > 0) {
                            loads.add(new TransportQuote.Load(rest, 1, batch.readyAt(), handed));
                        }
                        ResourceLocation resource = ResourceLocation.fromNamespaceAndPath("folkways",
                            "carrier/" + carrier.who().id() + "/pack");
                        best = new TransportQuote(ID, link, batch, batch.count(), cost, handed, loads,
                            List.of(new TransportQuote.Occupancy(resource, ITEMS, Math.min(room, batch.count()))),
                            Map.of(ID, snapshot.version()), Optional.of(carrier.who().id()));
                    }
                }
            }
        }
        if (best != null) {
            return new QuoteResult.Available(best, !pending);
        }
        return pending ? QuoteResult.LATER : QuoteResult.NO;
    }

    @Override
    public LayResult lay(TransportQuote quote, TransportLaying plan) {
        return plan.direct(quote) ? LayResult.LAID : LayResult.UNAVAILABLE;
    }
}
