package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.transport.api.BatchRequest;
import io.github.izakyl.folkways.core.transport.api.QuoteResult;
import io.github.izakyl.folkways.core.transport.api.TransportEndpoint;
import io.github.izakyl.folkways.core.transport.api.TransportLink;
import io.github.izakyl.folkways.core.transport.api.TransportPublication;
import io.github.izakyl.folkways.core.transport.api.TransportQuery;
import io.github.izakyl.folkways.core.transport.api.TransportQuote;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongConsumer;
import net.minecraft.resources.ResourceLocation;


// Prices one whole batch without searching: carried straight there, or carried to one published trunk, sent along
// it, and carried on from where it lands. Trunks never chain; a mode that composes lines does so inside its own link.
public final class TransportEstimate {

    // Each leg names the offering that quoted it, so expansion lays it with the same provider and snapshot.
    public record Leg(TransportPublication.Offering by, TransportQuote quote) {
    }

    public record Route(List<Leg> legs, long cost, long handedAt, long publicationVersion) {

        public Route {
            if (legs.isEmpty()) {
                throw new IllegalArgumentException("a route has at least one leg");
            }
            legs = List.copyOf(legs);
        }

        public Optional<Leg> trunk() {
            return legs.stream().filter(leg -> !leg.quote().mode().equals(DirectTransport.ID)).findFirst();
        }
    }

    public sealed interface Result {
    }

    // The cheapest route known. Incomplete when a candidate is still unknown, so it is not proven cheapest.
    public record Best(Route route, boolean complete) implements Result {
    }

    // No route is known yet, but some candidate is still unknown.
    public record Pending() implements Result {
    }

    public record Unreachable() implements Result {
    }

    public record Connection(ResourceLocation mode, TransportLink link) {
    }

    // A carry between two endpoints: needless when they are the same endpoint, else quoted, refused or unknown.
    private record Carry(boolean needless, Optional<TransportQuote> quote, boolean known) {
        static final Carry NEEDLESS = new Carry(true, Optional.empty(), true);
        static final Carry NONE = new Carry(false, Optional.empty(), true);
        static final Carry LATER = new Carry(false, Optional.empty(), false);

        boolean possible() {
            return needless || quote.isPresent();
        }

        long cost() {
            return quote.map(TransportQuote::cost).orElse(0L);
        }

        long handedAt(long readyAt) {
            return quote.map(TransportQuote::handedAt).orElse(readyAt);
        }
    }

    private record Access(TransportEndpoint from, TransportEndpoint to, long readyAt) {
    }

    private TransportEstimate() {
    }

    public static Result estimate(TransportQuery query, TransportPublication publication,
                                  TransportPublication.Offering direct) {
        return estimate(query, publication, direct, Set.of(), count -> { });
    }

    // `partial` hears of every trunk that could take only part of the batch, so a smaller batch can be tried.
    public static Result estimate(TransportQuery query, TransportPublication publication,
                                  TransportPublication.Offering direct, Set<Connection> excluded,
                                  LongConsumer partial) {
        BatchRequest batch = query.batch();
        Map<Access, Carry> carries = new HashMap<>();
        boolean complete = direct.complete();
        Route best = null;

        Carry whole = carry(direct, query.from(), query.to(), batch, batch.readyAt(), carries);
        complete &= whole.known();
        if (whole.quote().isPresent()
                && !excluded.contains(new Connection(direct.mode().id(), whole.quote().get().link()))) {
            TransportQuote quote = whole.quote().get();
            best = new Route(List.of(new Leg(direct, quote)), quote.cost(), quote.handedAt(), publication.version());
        }

        for (var entry : publication.entries()) {
            var offering = entry.offering();
            complete &= offering.complete();
            for (TransportLink link : offering.mode().links(query, offering.snapshot())) {
                if (excluded.contains(new Connection(offering.mode().id(), link)) || !handsOver(link)) {
                    continue;
                }
                if (!link.accepts().isEmpty() && link.accepts().stream()
                        .noneMatch(goods -> Goods.overlap(goods, batch.goods()))) {
                    continue;
                }
                Carry in = carry(direct, query.from(), link.from(), batch, batch.readyAt(), carries);
                complete &= in.known();
                if (!in.possible()) {
                    continue;
                }
                // No leg costs less than nothing, so a trunk whose approach alone costs as much cannot win.
                if (best != null && in.cost() >= best.cost()) {
                    continue;
                }
                BatchRequest sent = new BatchRequest(batch.goods(), batch.count(), in.handedAt(batch.readyAt()));
                QuoteResult answer = offering.mode().quote(link, sent, offering.snapshot());
                if (answer instanceof QuoteResult.Unknown) {
                    complete = false;
                    continue;
                }
                if (!(answer instanceof QuoteResult.Available(TransportQuote trunk, boolean known))) {
                    continue;
                }
                complete &= known;
                if (!trunk.mode().equals(offering.mode().id()) || !trunk.link().equals(link)
                        || !trunk.batch().equals(sent)) {
                    throw new IllegalArgumentException("quote does not answer this request");
                }
                if (trunk.covered() != batch.count()) {
                    partial.accept(trunk.covered());
                    continue;
                }
                long through = Costs.plus(in.cost(), trunk.cost());
                if (through == Costs.UNREACHABLE || best != null && through >= best.cost()) {
                    continue;
                }
                Carry out = carry(direct, link.to(), query.to(), batch, trunk.handedAt(), carries);
                complete &= out.known();
                if (!out.possible()) {
                    continue;
                }
                long cost = Costs.plus(through, out.cost());
                if (cost == Costs.UNREACHABLE || best != null && cost >= best.cost()) {
                    continue;
                }
                List<Leg> legs = new ArrayList<>(3);
                in.quote().ifPresent(quote -> legs.add(new Leg(direct, quote)));
                legs.add(new Leg(offering, trunk));
                out.quote().ifPresent(quote -> legs.add(new Leg(direct, quote)));
                best = new Route(legs, cost, out.handedAt(trunk.handedAt()), publication.version());
            }
        }
        if (best != null) {
            return new Best(best, complete);
        }
        return complete ? new Unreachable() : new Pending();
    }

    // A line loads and unloads through its own nodes; one that would keep goods in a store is not taken.
    private static boolean handsOver(TransportLink link) {
        return link.from().handover().form() == TransportEndpoint.Form.PACK
            && link.to().handover().form() == TransportEndpoint.Form.PACK;
    }

    private static Carry carry(TransportPublication.Offering direct, TransportEndpoint from, TransportEndpoint to,
                               BatchRequest batch, long readyAt, Map<Access, Carry> carries) {
        if (from.equals(to)) {
            return Carry.NEEDLESS;
        }
        return carries.computeIfAbsent(new Access(from, to, readyAt), key -> {
            BatchRequest leg = new BatchRequest(batch.goods(), batch.count(), readyAt);
            TransportQuery asked = new TransportQuery(from, to, leg, List.of(to));
            for (TransportLink link : direct.mode().links(asked, direct.snapshot())) {
                if (!link.from().equals(from) || !link.to().equals(to)) {
                    continue;
                }
                QuoteResult answer = direct.mode().quote(link, leg, direct.snapshot());
                if (answer instanceof QuoteResult.Unknown) {
                    return Carry.LATER;
                }
                if (answer instanceof QuoteResult.Available(TransportQuote quote, boolean known)
                        && quote.covered() == batch.count()) {
                    return new Carry(false, Optional.of(quote), known);
                }
                return Carry.NONE;
            }
            return Carry.NONE;
        });
    }
}
