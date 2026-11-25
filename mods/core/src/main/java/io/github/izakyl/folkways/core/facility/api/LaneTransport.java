package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.FolkwaysConfig;
import io.github.izakyl.folkways.core.facility.api.Contribution.Lane.PortInput;
import io.github.izakyl.folkways.core.facility.api.Contribution.Lane.WrittenInput;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.control.PartEndpoint;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
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
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Readiness;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Stand;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class LaneTransport implements TransportMode {

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "lane");
    private static final ResourceLocation BATCH = ID.withPath("lane_batch");
    private static final long RETRY_TICKS = 1200;

    private final ServerLevel level;
    private final Contribution.Lane lane;
    private final UUID key;
    private final Optional<PartEndpoint> input;
    private final AtomicBoolean collecting = new AtomicBoolean();
    private final Rig.Part inlet;
    private final Rig.Part outlet;
    private final TransportEndpoint from;
    private final TransportEndpoint to;
    private volatile boolean active = true;
    private volatile long retryAt;

    private LaneTransport(ServerLevel level, UUID facility, Contribution.Lane lane, Optional<PartEndpoint> input,
                          Rig.Part inlet, Rig.Part outlet) {
        this.level = level;
        this.lane = lane;
        this.input = input;
        key = UUID.nameUUIDFromBytes((facility + "/" + lane).getBytes(StandardCharsets.UTF_8));
        this.inlet = inlet;
        this.outlet = outlet;
        from = endpoint(inlet);
        to = endpoint(outlet);
    }

    static Optional<LaneTransport> observe(ServerLevel level, UUID facility, Contribution.Lane lane,
                                           Map<String, ControlRef> controls) {
        var outlet = port(level, lane.to())
            .flatMap(out -> Rig.of(level, Map.of("to", out)).part("to"));
        return outlet.flatMap(to -> switch (lane.from()) {
            case PortInput input -> controls.entrySet().stream()
                .filter(entry -> entry.getValue().equals(input.ref()))
                .map(entry -> new PartEndpoint(facility, entry.getKey(), input.ref())).findFirst()
                .flatMap(endpoint -> port(level, input.ref())
                    .flatMap(in -> Rig.of(level, Map.of("from", in)).part("from"))
                    .map(from -> new LaneTransport(level, facility, lane, Optional.of(endpoint), from, to)));
            case WrittenInput input -> Control.reach(level, input.at()).map(stance ->
                new LaneTransport(level, facility, lane, Optional.empty(),
                    new Rig.Part("from", new ControlRef(input.at(), ID.toString()), new WorkSite.AtBlock(input.at()),
                        stance, Control.Cost.INSTANT, new Rig.Ported(ResourceKind.ITEM), Optional.empty()), to));
        });
    }

    Optional<Workshop> transit() {
        if (!lane.automatic()) {
            return Optional.empty();
        }
        Map<ItemSpec, Long> goods = new LinkedHashMap<>();
        for (ControlRef ref : List.of(input.orElseThrow().ref(), lane.to())) {
            port(level, ref).stream().flatMap(port -> port.contents(level).stream())
                .filter(stack -> !stack.isEmpty())
                .forEach(stack -> goods.merge(Goods.specOf(stack), (long) stack.getCount(), Long::sum));
        }
        return goods.isEmpty() ? Optional.empty() : Optional.of(new Transit(this, Map.copyOf(goods)));
    }

    // These goods can only be promised by the lane, at the outlet.
    private record Transit(LaneTransport lane, Map<ItemSpec, Long> goods) implements Workshop {

        @Override
        public UUID key() {
            return lane.key;
        }

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public WorkSite site() {
            return lane.outlet.site();
        }

        @Override
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce produce) || lane.collecting.get()) {
                return List.of();
            }
            return goods.entrySet().stream()
                .filter(entry -> lane.carries(entry.getKey()) && Goods.includes(produce.wanted(), entry.getKey()))
                .map(entry -> {
                    long limit = Math.min(entry.getValue(), Goods.stackSize(entry.getKey()));
                    long count = produce.runs(site(), List.of(), 1, Optional.empty(), limit);
                    var shipment = lane.new Shipment(new BatchRequest(entry.getKey(), count, 0), count, true);
                    return Refinement.Change.of(Grown.of(shipment.new Arrival())).holding(lane.booking());
                }).toList();
        }
    }

    private Hold booking() {
        return new Hold() {
            private boolean taken;

            @Override
            public Optional<RefusalKind> take() {
                taken = active && collecting.compareAndSet(false, true);
                return taken ? Optional.empty() : Optional.of(FacilityRefusal.BUSY);
            }

            @Override
            public void release(Ending how) {
                if (taken) {
                    taken = false;
                    collecting.set(false);
                }
            }
        };
    }

    boolean available(long now) {
        return active && now >= retryAt;
    }

    void close() {
        active = false;
    }

    TransportPublication.Offering offering() {
        return new TransportPublication.Offering(this, new TransportSnapshot(0, 0, TransportTravel.UNKNOWN, List.of()),
            true);
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public List<TransportLink> links(TransportQuery query, TransportSnapshot snapshot) {
        if (!lane.from().at().sameRealm(query.from().at()) || !lane.to().at().sameRealm(query.to().at())
                || !carries(query.batch().goods())) {
            return List.of();
        }
        long capacity = Goods.stackSize(query.batch().goods());
        if (capacity <= 0) {
            return List.of();
        }
        ResourceLocation edge = name(lane.from().at() + "/" + from + " -> " + lane.to());
        return List.of(new TransportLink(edge, snapshot.version(), from, to, lane.items(),
            new TransportLink.Capacity(BATCH, capacity, capacity, 1, false),
            new TransportLink.Service(edge, snapshot.version(), Long.MIN_VALUE, Long.MAX_VALUE),
            true, true, false));
    }

    private boolean carries(ItemSpec goods) {
        return lane.items().isEmpty() || lane.items().stream().anyMatch(item -> Goods.includes(item, goods));
    }

    @Override
    public QuoteResult quote(TransportLink link, BatchRequest batch, TransportSnapshot snapshot) {
        long count = Math.min(batch.count(), link.capacity().perLoad());
        long travel = lane.travelTicks(count);
        long handed = batch.readyAt() + travel;
        return new QuoteResult.Available(new TransportQuote(ID, link, batch, count, TransportQuote.delay(travel),
            handed, List.of(new TransportQuote.Load(count, 1, batch.readyAt(), handed)), List.of(),
            Map.of(ID, snapshot.version()), Optional.empty()), true);
    }

    @Override
    public LayResult lay(TransportQuote quote, TransportLaying plan) {
        if (!available(quote.batch().readyAt())) {
            return LayResult.UNAVAILABLE;
        }
        Shipment shipment = new Shipment(quote.batch(), quote.covered(), false);
        Node arrival = shipment.new Arrival();
        plan.add(arrival);
        if (!plan.hold(booking())) {
            return LayResult.UNAVAILABLE;
        }
        Node send = input.<Node>map(endpoint -> {
            Delivery delivery = Delivery.toPart(UUID.randomUUID(), ID, endpoint, inlet.stances(),
                shipment.batch.goods(), shipment.count, moved -> { });
            Node transfer = plan.delivery(delivery);
            return new Node() {
                @Override
                public NodeSpec spec() {
                    return transfer.spec();
                }

                @Override
                public Outcome commit(ServerLevel level, Worker worker) {
                    if (!active || port(level, lane.to()).isEmpty()) {
                        return Outcome.failed(FacilityRefusal.GONE);
                    }
                    long before = shipment.total(shipment.matching(level));
                    Outcome result = transfer.commit(level, worker);
                    if (result instanceof Outcome.Done done) {
                        shipment.shortLoad = !done.unused().isEmpty();
                        shipment.sent(level, before);
                    }
                    return result;
                }
            };
        }).orElseGet(shipment::newSend);
        plan.add(send);
        if (!plan.before(send.id(), arrival.id()) || !plan.feed(send.id())) {
            return LayResult.UNAVAILABLE;
        }
        return plan.output(arrival.id()) ? LayResult.LAID : LayResult.UNAVAILABLE;
    }

    private static Optional<Port<ItemStack>> port(ServerLevel level, ControlRef ref) {
        return Ports.at(level, ref.at(), ResourceKind.ITEM, ref.part()).filter(port -> port.valid(level));
    }

    private static TransportEndpoint endpoint(Rig.Part part) {
        if (part == null) {
            return null;
        }
        ResourceLocation id = name(part.ref().toString());
        return new TransportEndpoint(id, part.ref().at(),
            part.stances().cells().stream().map(Stand::new).collect(Collectors.toSet()),
            new TransportEndpoint.Handover(TransportEndpoint.Form.PACK, id));
    }

    private static ResourceLocation name(String value) {
        return ID.withPath(ID.getPath() + "/" + UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)));
    }

    private final class Shipment {
        private final BatchRequest batch;
        private final int count;
        private final boolean alreadyMoving;
        private long baseline;
        private long deadline = -1;
        private boolean shortLoad;

        private Optional<RefusalKind> unavailable(ServerLevel level, FacilityRefusal why) {
            retryAt = level.getGameTime() + RETRY_TICKS;
            return Optional.of(why);
        }

        Shipment(BatchRequest batch, long count, boolean alreadyMoving) {
            this.batch = batch;
            this.count = (int) count;
            this.alreadyMoving = alreadyMoving;
        }

        private Node newSend() {
            return new Send();
        }

        private List<ItemStack> matching(ServerLevel level) {
            return port(level, lane.to()).stream().flatMap(out -> out.contents(level).stream())
                .filter(stack -> Goods.matches(stack, batch.goods())).toList();
        }

        private long total(List<ItemStack> stacks) {
            return stacks.stream().mapToLong(ItemStack::getCount).sum();
        }

        // Native machinery owns the goods until a new batch is visible at the outlet.
        private void sent(ServerLevel level, long before) {
            baseline = before;
            long expected = lane.travelTicks(count);
            deadline = level.getGameTime() + expected + Math.max(40, expected);
        }

        private final class Send implements Node {
            private final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), ID, inlet.site(), inlet.stances(),
                    Workload.Once.of(0))
                .needs(new Need(batch.goods(), count, true)).vocation(Vocations.required(Vocations.HAULING)).done();

            @Override
            public NodeSpec spec() {
                return spec;
            }

            @Override
            public Outcome commit(ServerLevel level, Worker worker) {
                if (!active || port(level, lane.to()).isEmpty()) {
                    return Outcome.failed(FacilityRefusal.GONE);
                }
                return load(level, worker, ((WrittenInput) lane.from()).write());
            }

            private Outcome load(ServerLevel level, Worker worker, Contribution.Lane.Write write) {
                List<ItemStack> unused = worker.supplied().stream().map(ItemStack::copy).toList();
                List<ItemStack> supplied = unused.stream().filter(item -> Goods.matches(item, batch.goods()))
                    .toList();
                if (total(supplied) < count) {
                    return Outcome.failed(FacilityRefusal.REFUSED);
                }
                long before = total(matching(level));
                var at = lane.from().at();
                ItemStack offered = supplied.getFirst().copyWithCount(count);
                if (write.deposit(level, at, worker.body(), offered.copy(), true) != count) {
                    return Outcome.failed(FacilityRefusal.BUSY);
                }
                int moved = Math.clamp(write.deposit(level, at, worker.body(), offered, false), 0, count);
                shortLoad = moved != count;
                for (ItemStack stack : supplied) {
                    int used = Math.min(moved, stack.getCount());
                    stack.shrink(used);
                    moved -= used;
                }
                sent(level, before);
                InventoryFeedback.store(level, at);
                return new Outcome.Done(List.of(), List.of(), Optional.empty(),
                    unused.stream().filter(item -> !item.isEmpty()).toList());
            }
        }

        private final class Arrival implements Node {
            private final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), ID, outlet.site(), outlet.stances(),
                    Workload.Once.of(FolkwaysConfig.haulReachTicks()))
                .gives(new Amount(batch.goods(), count)).vocation(Vocations.required(Vocations.HAULING)).done();

            @Override
            public NodeSpec spec() {
                return spec;
            }

            @Override
            public Optional<RefusalKind> planned(ServerLevel level) {
                if (alreadyMoving && deadline < 0) {
                    PortInput input = (PortInput) lane.from();
                    if (!active || port(level, input.ref()).isEmpty() || port(level, lane.to()).isEmpty()) {
                        return unavailable(level, FacilityRefusal.GONE);
                    }
                    sent(level, 0);
                }
                return Optional.empty();
            }

            @Override
            public Readiness readiness(ServerLevel level) {
                return Readiness.of(deadline >= 0 && total(matching(level)) - baseline >= count);
            }

            @Override
            public Optional<RefusalKind> refusal(ServerLevel level) {
                if (!active || port(level, lane.to()).isEmpty()) {
                    return unavailable(level, FacilityRefusal.GONE);
                }
                if (shortLoad || deadline >= 0 && level.getGameTime() >= deadline
                        && total(matching(level)) - baseline < count) {
                    return unavailable(level, FacilityRefusal.UNSETTLED);
                }
                return Optional.empty();
            }

            @Override
            public Outcome commit(ServerLevel level, Worker worker) {
                var output = port(level, lane.to());
                if (!active || output.isEmpty()) {
                    return Outcome.failed(FacilityRefusal.GONE);
                }
                List<ItemStack> current = matching(level);
                if (total(current) - baseline < count) {
                    return Outcome.failed(unavailable(level, FacilityRefusal.UNSETTLED).orElseThrow());
                }
                var lease = Lease.take(output.get(), worker);
                if (lease.isEmpty()) {
                    return Outcome.failed(FacilityRefusal.BUSY);
                }
                try (Lease ignored = lease.get()) {
                    ItemStack wanted = current.getFirst().copyWithCount(count);
                    if (output.get().draw(level, worker.body(), wanted, true).getCount() != count) {
                        return Outcome.failed(unavailable(level, FacilityRefusal.REFUSED).orElseThrow());
                    }
                    ItemStack arrived = output.get().draw(level, worker.body(), wanted, false);
                    if (arrived.getCount() != count) {
                        unavailable(level, FacilityRefusal.REFUSED);
                    }
                    InventoryFeedback.store(level, lane.to().at());
                    return new Outcome.Done(List.of(), List.of(arrived), Optional.empty());
                }
            }
        }
    }
}
