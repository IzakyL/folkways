package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.plan.api.TransportIntent;
import io.github.izakyl.folkways.core.plan.internal.Transfer.From;
import io.github.izakyl.folkways.core.plan.internal.Transfer.To;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.transport.api.BatchRequest;
import io.github.izakyl.folkways.core.transport.api.LayResult;
import io.github.izakyl.folkways.core.transport.api.QuoteResult;
import io.github.izakyl.folkways.core.transport.api.TransportEndpoint;
import io.github.izakyl.folkways.core.transport.api.TransportLaying;
import io.github.izakyl.folkways.core.transport.api.TransportLink;
import io.github.izakyl.folkways.core.transport.api.TransportMode;
import io.github.izakyl.folkways.core.transport.api.TransportPublication;
import io.github.izakyl.folkways.core.transport.api.TransportQuery;
import io.github.izakyl.folkways.core.transport.api.TransportQuote;
import io.github.izakyl.folkways.core.transport.api.TransportSnapshot;
import io.github.izakyl.folkways.core.transport.api.TransportTravel;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.internal.TransferNode;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

final class Routing implements Refinement {

    interface Plan extends io.github.izakyl.folkways.core.plan.internal.Laying {
        Transfer transfer();

        Situation situation();

        Crew crew();

        Set<Stand> stands(UUID node);

        boolean eligible(Crew.Hand hand, UUID node);

        boolean hold(Hold hold);

        boolean after(UUID node, Set<UUID> predecessors);

        boolean reserve(ResourceLocation resource, ResourceLocation unit, UUID owner, long amount, long capacity);
    }

    private record Route(ResourceLocation kind, WorldPos from, WorldPos to) {
    }

    private record Price(TransportEndpoint from, TransportEndpoint to, ItemSpec goods, long count, long readyAt,
                         long version) {
    }

    private record Asking(Resident who, WorldPos from) {
    }

    static final class Limited extends RuntimeException {
        private Limited() {
            super("transport expansion budget exhausted", null, false, false);
        }
    }

    // One top-level Transfer's allowance. Legs carry directly, so nothing nests inside it.
    private static final class Session {
        private int operations = 256;
        private int nodes = 4_096;
        private boolean limited;

        private void use() {
            if (operations-- <= 0) {
                limited = true;
                throw new Limited();
            }
        }

        private void nodes(int count) {
            if (count > nodes) {
                limited = true;
                throw new Limited();
            }
            nodes -= count;
        }
    }

    // A route that failed to lay is rejected and the next cheapest is laid instead, a few times at most.
    private static final int MOST_ATTEMPTS = 4;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("folkways-labor");
    private final Set<Route> requested = new HashSet<>();
    // Travel known this planning round, shared by every price asked for in it.
    private final Map<Route, TransportTravel.Result> known = new HashMap<>();
    private final Carry direct = new Carry();
    private final Map<Price, OptionalLong> prices = new HashMap<>();
    private TransportPublication publication;
    private Session session;

    void begin(TransportPublication publication) {
        this.publication = publication;
        prices.clear();
        requested.clear();
        known.clear();
    }

    @Override
    public ResourceLocation id() {
        return TransportIntent.OWNER;
    }

    @Override
    public OptionalLong lay(Intent intent, Context context) {
        if (!(intent instanceof TransportIntent transport)
                || context.laying().filter(Plan.class::isInstance).isEmpty()) {
            return OptionalLong.empty();
        }
        Plan plan = (Plan) context.laying().orElseThrow();
        Transfer transfer = plan.transfer();
        if (!transfer.intent().equals(transport)) {
            throw new IllegalArgumentException("transport has no material binding in this expansion");
        }
        if (session != null) {
            throw new IllegalStateException("trunks never chain; their material legs must carry directly");
        }
        if (context.planning().orElseThrow().transports().entries().isEmpty()) {
            return OptionalLong.of(plan.attempt(() -> direct.lay(transfer, plan)));
        }
        if (transfer.to() instanceof To.Feeding feeding && !feeding.edge().carried()
                && transfer.from() instanceof From.Stocked stocked
                && !plan.reaching(feeding.edge().consumer(), stocked.where()).isEmpty()) {
            // Stock within reach takes no carrying work and needs no carrier quote.
            return OptionalLong.of(plan.attempt(() -> direct.lay(transfer, plan)));
        }
        session = new Session();
        try {
            return OptionalLong.of(plan.attempt(() -> expand(transfer, plan)));
        } catch (Limited exhausted) {
            plan.because(Why.NO_SOURCE);
            return OptionalLong.of(0);
        } finally {
            session = null;
        }
    }

    // What carrying the goods between two places would cost by the cheapest route known: straight there, or by one
    // trunk. Unknown when no route is known yet; no way at all when every candidate is refused.
    @Override
    public OptionalLong estimate(Intent intent, Context context) {
        if (!(intent instanceof TransportIntent transport) || context.planning().isEmpty()) {
            return OptionalLong.empty();
        }
        Planning now = context.planning().orElseThrow();
        if (now.transports().entries().isEmpty()) {
            return OptionalLong.of(Transfer.walk(transport.from().at(), new WorkSite.AtBlock(transport.to().at())));
        }
        if (transport.count() <= 0 || transport.goods().item().isEmpty()) {
            return OptionalLong.empty();
        }
        Price price = new Price(transport.from(), transport.to(), transport.goods(), transport.count(),
            transport.readyAt(), now.transports().version());
        return prices.computeIfAbsent(price, ignored -> price(transport, now));
    }

    private OptionalLong price(TransportIntent transport, Planning now) {
        TransportSnapshot view = now.snapshot(transport.goods());
        TransportTravel cached = (kind, start, end, departure) -> known.computeIfAbsent(new Route(kind, start, end),
            ignored -> view.travel().estimate(kind, start, end, departure));
        var snapshot = new TransportSnapshot(view.version(), view.at(), cached, view.carriers());
        var direct = new TransportPublication.Offering(new DirectTransport(), snapshot, true);
        TransportEndpoint from = transport.from();
        TransportEndpoint to = transport.to();
        ItemSpec goods = transport.goods();
        long count = transport.count();
        long[] most = {0};
        var result = TransportEstimate.estimate(
            new TransportQuery(from, to, new BatchRequest(goods, count, transport.readyAt()), List.of()),
            now.transports(), direct, Set.of(), covered -> most[0] = Math.max(most[0], covered));
        if (!(result instanceof TransportEstimate.Best) && most[0] > 0 && most[0] < count) {
            // A trunk that takes only part of the batch at a time is priced as that part, sent as often as needed.
            var part = TransportEstimate.estimate(
                new TransportQuery(from, to, new BatchRequest(goods, most[0], transport.readyAt()), List.of()),
                now.transports(), direct);
            if (part instanceof TransportEstimate.Best best) {
                long runs = (count - 1) / most[0] + 1;
                return OptionalLong.of(best.route().cost() > Costs.UNREACHABLE / runs ? Costs.UNREACHABLE - 1
                    : best.route().cost() * runs);
            }
            result = part;
        }
        return switch (result) {
            case TransportEstimate.Best best -> OptionalLong.of(best.route().cost());
            case TransportEstimate.Pending ignored -> OptionalLong.empty();
            case TransportEstimate.Unreachable ignored -> OptionalLong.of(Costs.UNREACHABLE);
        };
    }

    private long expand(Transfer transfer, Plan plan) {
        // Pack manifests can contain alternatives: route each concrete share without changing its identity.
        if (transfer.goods().item().isEmpty() && transfer.from() instanceof From.Carried carried) {
            long moved = 0;
            for (var stack : carried.stacks()) {
                long count = expand(request(new From.Carried(carried.hand(), List.of(stack)), transfer.to(),
                    Goods.specOf(stack), stack.getCount(), transfer.intent().readyAt(), plan), plan);
                if (count != stack.getCount()) {
                    return 0;
                }
                moved += count;
            }
            return moved;
        }
        if (transfer.goods().item().isEmpty()) {
            plan.because(Why.NO_SOURCE);
            return 0;
        }
        java.util.NavigableSet<Long> batches = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
        batches.add(transfer.count());
        // A direct quote may cover repeated trips while Carry lays just one pack's share at a time.
        batches.add(Math.min(transfer.count(), plan.perTrip(transfer.goods())));
        for (int attempt = 0; attempt < 16 && !batches.isEmpty(); attempt++) {
            long count = batches.pollFirst();
            From source = transfer.from();
            if (source instanceof From.Carried carried && count < transfer.count()) {
                List<net.minecraft.world.item.ItemStack> stacks = new ArrayList<>();
                long left = count;
                for (var stack : carried.stacks()) {
                    int take = (int) Math.min(left, stack.getCount());
                    if (take > 0) {
                        stacks.add(stack.copyWithCount(take));
                        left -= take;
                    }
                }
                source = new From.Carried(carried.hand(), stacks);
            }
            Transfer part = request(source, transfer.to(), transfer.goods(), count, transfer.intent().readyAt(), plan);
            long moved = route(part, plan, batches);
            if (moved > 0) {
                return moved;
            }
        }
        return 0;
    }

    private long route(Transfer transfer, Plan plan, Set<Long> batches) {
        TransportEndpoint from = transfer.intent().from();
        TransportEndpoint to = transfer.intent().to();
        BatchRequest batch = new BatchRequest(transfer.goods(), transfer.count(), transfer.intent().readyAt());
        Set<TransportEstimate.Connection> rejected = new HashSet<>();
        for (int attempt = 0; attempt < MOST_ATTEMPTS; attempt++) {
            session.use();
            if (publication.version() != plan.situation().transportVersion().getAsLong()) {
                // A refused expansion may have withdrawn its publication; try again with what stands now.
                publication = plan.situation().transports().get();
            }
            Map<Route, TransportTravel.Result> routes = new LinkedHashMap<>();
            var result = TransportEstimate.estimate(new TransportQuery(from, to, batch, List.of()), publication,
                carrying(transfer, plan, from, to, routes), rejected, count -> {
                    if (count < transfer.count()) {
                        batches.add(count);
                    }
                });
            explore(plan, routes);
            if (!(result instanceof TransportEstimate.Best best)) {
                plan.because(result instanceof TransportEstimate.Unreachable && !session.limited
                    ? Why.NO_WAY : Why.NO_SOURCE);
                return 0;
            }
            TransportEstimate.Route chosen = best.route();
            long moved = plan.attempt(() -> {
                try {
                    return laid(chosen, transfer, plan) ? transfer.count() : 0;
                } catch (Limited exhausted) {
                    throw exhausted;
                } catch (RuntimeException | LinkageError broken) {
                    // A route that breaks while laid is refused like one that does not fit; the next is tried.
                    LOGGER.warn("transport expansion failed for {}", transfer.goods().describe(), broken);
                    return 0;
                }
            });
            if (moved > 0) {
                return moved;
            }
            // The trunk is what made the route; without one, the straight carry itself is refused.
            TransportQuote refused = chosen.trunk().orElse(chosen.legs().getFirst()).quote();
            rejected.add(new TransportEstimate.Connection(refused.mode(), refused.link()));
        }
        plan.because(Why.NO_SOURCE);
        return 0;
    }

    // Lays every leg of the route in order, each continuing from what the one before hands over.
    private boolean laid(TransportEstimate.Route chosen, Transfer transfer, Plan plan) {
        From current = transfer.from();
        List<TransportEstimate.Leg> legs = chosen.legs();
        for (int i = 0; i < legs.size(); i++) {
            TransportEstimate.Leg step = legs.get(i);
            if (step.quote().mode().equals(DirectTransport.ID) && i + 1 < legs.size()) {
                // The next line's loading is fed from where the goods are: this carry is its feeding.
                continue;
            }
            Leg leg = new Leg(plan, current, transfer, step.quote(), i == legs.size() - 1);
            if (stale(plan, chosen, step) || step.by().mode().lay(step.quote(), leg) != LayResult.LAID
                    || !leg.valid() || stale(plan, chosen, step)) {
                return false;
            }
            current = leg.output;
        }
        return legs.getLast().quote().mode().equals(DirectTransport.ID) || finish(current, transfer, plan);
    }

    private static boolean stale(Plan plan, TransportEstimate.Route chosen, TransportEstimate.Leg step) {
        long version = step.by().snapshot().version();
        return plan.situation().transportVersion().getAsLong() != chosen.publicationVersion()
            || !step.quote().versions().getOrDefault(step.by().mode().id(), version).equals(version);
    }

    // The direct way as this Transfer may use it: the first carry is done by whoever may take from the source, the
    // last by whoever may do the consumer's work.
    private TransportPublication.Offering carrying(Transfer transfer, Plan plan, TransportEndpoint from,
                                                   TransportEndpoint to, Map<Route, TransportTravel.Result> routes) {
        TransportSnapshot snapshot = snapshot(transfer, plan, routes);
        Set<UUID> sourceHands = new HashSet<>();
        Set<UUID> destinationHands = new HashSet<>();
        for (Crew.Hand hand : plan.crew().hands()) {
            boolean sourceFits = !(transfer.from() instanceof From.Carried carried)
                || carried.hand().equals(hand.id());
            if (transfer.from() instanceof From.Made made && plan.putsInto(made.maker()).isEmpty()) {
                sourceFits &= plan.eligible(hand, made.maker());
            }
            if (sourceFits) {
                sourceHands.add(hand.id());
            }
            if (!(transfer.to() instanceof To.Feeding feeding) || plan.eligible(hand, feeding.edge().consumer())) {
                destinationHands.add(hand.id());
            }
        }
        DirectTransport mode = new DirectTransport();
        TransportMode quoting = new TransportMode() {
            public ResourceLocation id() {
                return mode.id();
            }

            public List<TransportLink> links(TransportQuery query, TransportSnapshot view) {
                return mode.links(query, view);
            }

            public QuoteResult quote(TransportLink link, BatchRequest request, TransportSnapshot view) {
                List<TransportSnapshot.Carrier> carriers = view.carriers().stream()
                    .filter(carrier -> !link.from().equals(from) || sourceHands.contains(carrier.who().id()))
                    .filter(carrier -> !link.to().equals(to) || destinationHands.contains(carrier.who().id()))
                    .toList();
                return mode.quote(link, request, new TransportSnapshot(view.version(), view.at(), view.travel(),
                    carriers));
            }

            public LayResult lay(TransportQuote quote, TransportLaying growth) {
                return mode.lay(quote, growth);
            }
        };
        return new TransportPublication.Offering(quoting, snapshot, true);
    }

    private TransportSnapshot snapshot(Transfer transfer, Plan plan, Map<Route, TransportTravel.Result> routes) {
        TransportTravel view = plan.situation().ways().estimates();
        TransportTravel cached = (kind, from, to, departure) -> routes.computeIfAbsent(new Route(kind, from, to),
            ignored -> view.estimate(kind, from, to, departure));
        List<TransportSnapshot.Carrier> carriers = new ArrayList<>();
        for (Crew.Hand hand : plan.crew().hands()) {
            long room = (long) hand.packCells() * Math.max(1, Goods.stackSize(transfer.goods()));
            if (transfer.from() instanceof From.Carried carried && carried.hand().equals(hand.id())) {
                room += carried.stacks().stream().filter(stack -> Goods.matches(stack, transfer.goods()))
                    .mapToLong(net.minecraft.world.item.ItemStack::getCount).sum();
            }
            if (hand.takes(Vocations.of(Vocations.HAULING)) && room > 0) {
                carriers.add(DirectTransport.carrier(hand.who(), hand.at(), transfer.goods(), room,
                    FolkwaysConfig.haulReachTicks()));
            }
        }
        return new TransportSnapshot(plan.situation().at(), plan.situation().at(), cached, carriers);
    }

    private void explore(Plan plan, Map<Route, TransportTravel.Result> routes) {
        Map<Asking, Set<WorldPos>> requests = new LinkedHashMap<>();
        for (var entry : routes.entrySet()) {
            if (entry.getValue() instanceof TransportTravel.Later && requested.add(entry.getKey())) {
                plan.crew().hands().stream().map(Crew.Hand::who)
                    .filter(who -> who.routeKind().equals(entry.getKey().kind())).findFirst().ifPresent(who ->
                        requests.computeIfAbsent(new Asking(who, entry.getKey().from()), key -> new LinkedHashSet<>())
                            .add(entry.getKey().to()));
            }
        }
        requests.forEach((asking, goals) -> plan.situation().ways().journey(asking.who(), asking.from(), goals));
    }

    static TransportEndpoint endpoint(String id, WorldPos at, Set<Stand> stands,
                                               TransportEndpoint.Form form) {
        ResourceLocation name = ResourceLocation.fromNamespaceAndPath("folkways", "transfer/" + id);
        return new TransportEndpoint(name, at, stands, new TransportEndpoint.Handover(form, name));
    }

    static Transfer request(From from, To to, ItemSpec goods, long count, long readyAt, Plan plan) {
        return new Transfer(TransportIntent.of(source(from, plan), destination(to, plan), goods, count, readyAt),
            from, to);
    }

    private static TransportEndpoint source(From from, Plan plan) {
        return switch (from) {
            case From.Stocked stocked -> endpoint("source", stocked.where().pos(), plan.around(stocked.where()),
                TransportEndpoint.Form.INVENTORY);
            case From.LeftBy left -> endpoint("source", left.out().pos(), plan.around(left.out()),
                TransportEndpoint.Form.INVENTORY);
            case From.Made made -> plan.putsInto(made.maker()).isEmpty()
                ? endpoint("source", plan.node(made.maker()).spec().site().where(), plan.stands(made.maker()),
                    TransportEndpoint.Form.PACK)
                : endpoint("source", plan.putsInto(made.maker()).getFirst().pos(),
                    plan.around(plan.putsInto(made.maker()).getFirst()), TransportEndpoint.Form.INVENTORY);
            case From.Carried carried -> {
                var hand = plan.crew().hands().stream().filter(one -> one.id().equals(carried.hand()))
                    .findFirst().orElseThrow();
                yield endpoint("source", hand.at(), Set.of(new Stand(hand.at())), TransportEndpoint.Form.PACK);
            }
        };
    }

    private static TransportEndpoint destination(To to, Plan plan) {
        return switch (to) {
            case To.Stored stored -> endpoint("destination", stored.into().pos(), plan.around(stored.into()),
                TransportEndpoint.Form.INVENTORY);
            case To.Feeding feeding -> endpoint("destination",
                plan.node(feeding.edge().consumer()).spec().site().where(), plan.stands(feeding.edge().consumer()),
                TransportEndpoint.Form.PACK);
        };
    }

    private long carry(Transfer transfer, Plan plan) {
        Laying.Mark mark = plan.mark();
        try {
            return direct.lay(transfer, plan);
        } finally {
            session.nodes(plan.laidSince(mark).size());
        }
    }

    private boolean finish(From from, Transfer transfer, Plan plan) {
        To to = transfer.to();
        if (to instanceof To.Stored stored && !(from instanceof From.Carried)) {
            if (plan.roomFor(stored.into(), transfer.goods()) < transfer.count()) {
                return false;
            }
            TransferNode put = TransferNode.relay(stored.into(), new Need(transfer.goods(), transfer.count(), true),
                plan.around(stored.into()));
            session.nodes(1);
            plan.add(put);
            plan.book(new Resource.Space(stored.into(), transfer.goods()), put.id(), transfer.count());
            plan.into(put.id(), stored.into());
            to = new To.Feeding(new Edge(UUID.randomUUID(), put.id(),
                new Need(transfer.goods(), transfer.count(), true), Set.of()));
        }
        Transfer last = request(from, to, transfer.goods(), transfer.count(), transfer.intent().readyAt(), plan);
        return carry(last, plan) == transfer.count();
    }

    private final class Leg implements TransportLaying {
        private final Plan plan;
        private final From source;
        private final Transfer transfer;
        private final TransportQuote quote;
        private final boolean last;
        private final Set<UUID> owned = new LinkedHashSet<>();
        private final Set<UUID> fed = new LinkedHashSet<>();
        private final Set<UUID> supplied = new LinkedHashSet<>();
        private final Set<UUID> waits = new LinkedHashSet<>();
        private final Map<UUID, Set<UUID>> after = new HashMap<>();
        private From output;
        private boolean delivered;

        private Leg(Plan plan, From source, Transfer transfer, TransportQuote quote, boolean last) {
            this.plan = plan;
            this.source = source;
            this.transfer = transfer;
            this.quote = quote;
            this.last = last;
        }

        private void owns(UUID id) {
            if (!owned.contains(id)) {
                throw new IllegalArgumentException("transport may only modify its own nodes");
            }
        }

        public void add(Node node) {
            session.use();
            session.nodes(1);
            if (owned.size() >= 256 || plan.node(node.id()) != null) {
                throw new IllegalArgumentException("transport growth exceeds its bounds");
            }
            plan.add(node);
            owned.add(node.id());
        }

        public boolean feed(UUID consumer) {
            session.use();
            owns(consumer);
            if (!fed.isEmpty()) {
                return false;
            }
            Need need = new Need(transfer.goods(), transfer.count(), true);
            if (plan.node(consumer).spec().needs().stream().noneMatch(one -> one.spec().equals(need.spec())
                    && one.count() == need.count())) {
                return false;
            }
            if (carry(request(source,
                    new To.Feeding(new Edge(UUID.randomUUID(), consumer, need, Set.of())),
                    need.spec(), need.count(), quote.batch().readyAt(), plan), plan) != need.count()) {
                return false;
            }
            fed.add(consumer);
            supplied.add(consumer);
            return true;
        }

        public boolean transfer(UUID maker, UUID consumer, ItemSpec goods, long count) {
            session.use();
            owns(maker);
            owns(consumer);
            if (supplied.contains(consumer) || count != transfer.count() || !goods.equals(transfer.goods())) {
                return false;
            }
            long yield = plan.node(maker).spec().gives().stream().filter(one -> one.spec().equals(goods))
                .mapToLong(Amount::count).sum();
            return plan.attempt(() -> {
                // A mode moving goods between its own nodes carries them directly, never by another trunk.
                long moved = carry(request(new From.Made(maker, yield),
                    new To.Feeding(new Edge(UUID.randomUUID(), consumer, new Need(goods, count, true), Set.of())),
                    goods, count, quote.batch().readyAt(), plan), plan);
                if (moved != count) {
                    return 0;
                }
                flow(maker, consumer, goods, count);
                supplied.add(consumer);
                return moved;
            }) == count;
        }

        public void flow(UUID from, UUID to, ItemSpec goods, long count) {
            session.use();
            owns(from);
            owns(to);
            if (!goods.equals(transfer.goods()) || count <= 0 || count > transfer.count()
                    || !plan.after(to, Set.of(from))) {
                throw new IllegalArgumentException("invalid transport material dependency");
            }
            plan.flow(Flow.from(from, to, goods, count));
            after.computeIfAbsent(to, key -> new LinkedHashSet<>()).add(from);
        }

        public boolean before(UUID first, UUID then) {
            session.use();
            owns(first);
            owns(then);
            if (!plan.after(then, Set.of(first))) {
                return false;
            }
            after.computeIfAbsent(then, key -> new LinkedHashSet<>()).add(first);
            return true;
        }

        public void together(UUID one, UUID other) {
            session.use();
            owns(one);
            owns(other);
            plan.together(one, other);
        }

        public boolean reserve(ResourceLocation resource, ResourceLocation unit, UUID owner,
                               long amount, long capacity) {
            session.use();
            owns(owner);
            return plan.reserve(resource, unit, owner, amount, capacity);
        }

        public boolean hold(Hold hold) {
            session.use();
            if (!plan.hold(hold)) {
                return false;
            }
            waits.addAll(hold.after());
            return true;
        }

        public boolean output(UUID maker) {
            session.use();
            owns(maker);
            if (output != null || plan.node(maker).spec().gives().stream()
                    .filter(one -> one.spec().equals(transfer.goods())).mapToLong(Amount::count).sum()
                    != transfer.count()) {
                return false;
            }
            output = new From.Made(maker, transfer.count());
            return true;
        }

        public boolean direct(TransportQuote selected) {
            if (!selected.equals(quote) || !selected.mode().equals(DirectTransport.ID)) {
                return false;
            }
            if (last) {
                if (transfer.to() instanceof To.Feeding feeding) {
                    quote.carrier().ifPresent(who -> plan.by(feeding.edge().consumer(), who));
                }
                delivered = finish(source, transfer, plan);
                return delivered;
            }
            // Short of the end, a carry only leads to the next line's loading, which feeds itself from the goods.
            return false;
        }

        private boolean valid() {
            if (delivered) {
                return true;
            }
            if (!(output instanceof From.Made made) || fed.isEmpty()) {
                return false;
            }
            Set<UUID> seen = new HashSet<>();
            List<UUID> pending = new ArrayList<>(List.of(made.maker()));
            while (!pending.isEmpty()) {
                UUID at = pending.removeLast();
                if (seen.add(at)) {
                    pending.addAll(after.getOrDefault(at, Set.of()));
                }
            }
            return seen.containsAll(owned) && owned.stream().allMatch(node -> plan.node(node).spec().needs().stream()
                .filter(need -> need.count() > 0).allMatch(need -> supplied.contains(node)
                    && need.spec().equals(transfer.goods()) && need.count() == transfer.count()))
                && owned.stream().allMatch(plan::fits)
                && owned.stream().allMatch(node -> plan.after(node, waits));
        }
    }
}
