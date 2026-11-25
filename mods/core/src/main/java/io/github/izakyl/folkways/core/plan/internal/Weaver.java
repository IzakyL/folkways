package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.FolkwaysConfig;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.facility.api.control.Port.Operation;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.work.api.ReservationPool;
import io.github.izakyl.folkways.core.plan.api.RunBoundary;
import io.github.izakyl.folkways.core.plan.api.ScopedWork;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.plan.api.Sizing;
import io.github.izakyl.folkways.core.plan.api.TransportIntent;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.plan.internal.Expansion.Touch;
import io.github.izakyl.folkways.core.plan.internal.Transfer.From;
import io.github.izakyl.folkways.core.plan.internal.Transfer.To;
import io.github.izakyl.folkways.core.resident.api.Gait;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.transport.api.TransportEndpoint;
import io.github.izakyl.folkways.core.transport.api.TransportPublication;
import io.github.izakyl.folkways.core.transport.api.TransportSnapshot;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.travel.internal.Urgency;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Manifest;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.Stall;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.ToolPolicy;
import io.github.izakyl.folkways.core.work.api.Unmet;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.HaulWork;
import io.github.izakyl.folkways.core.work.internal.TransferNode;
import io.github.izakyl.folkways.core.work.support.WorkStances;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plan: one graph of work that grows in only two ways.
 *
 * <p>An intent is expanded into the work a rule gives for it ({@link Refining}). An open edge - goods a node needs
 * that nothing feeds yet - grows a source: goods already in a worker's pack, goods in a store, or goods a workshop
 * offers to make, whose own needs are open edges in turn. Sources are tried cheapest first, by what they would cost
 * ({@link Costs}). A delivery is an intent; once its source is chosen, the way to its consumer is a
 * {@link TransportIntent}, priced and laid through the same refinement registry. The transport rule refines it
 * into direct carrying, or carrying to one trunk and carrying on, with material edges between the legs. Carrying
 * within a trunk route is always direct: trunks never chain.
 *
 * <p>The graph has two kinds of edge: links, which only order work, and flows, which carry goods into it. A flow
 * whose source goes is fed again some other way; a link whose earlier end goes undone is left to the owner of its
 * later end ({@link io.github.izakyl.folkways.core.work.api.Unmet}).
 *
 * <p>Each growth is kept as one {@link Expansion}; where a node stands, what it carries and who must do it are read
 * off the expansions. Taking a growth back is removing its expansion, which opens its edge again. What a node makes
 * beyond what was asked stays with whoever made it: clearing it is that work's own business.
 */
public final class Weaver {

    public static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_core", "supply");

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    // Open edges fed in one weave before it gives up as not converging: a floor, and so many more for each node
    // asked of the plan, as like demands joined onto one making still feed each of their edges.
    private static final int MOST_FILLS = 50_000;
    private static final int FILLS_PER_ASK = 24;
    private static final int MOST_JOINS_TRIED = 4;
    // How much nearer another of a workshop's alike ways must bring the work before the one taken is given up for it:
    // so many ticks at the least, and a quarter of what the one taken costs.
    private static final long SWITCH_TICKS = 100;
    private static final long SWITCH_SHARE = 4;

    private final Routing transport = new Routing();
    private final Refining refining = new Refining(rules(), this::lent);
    private final Claims claims;
    private final Cooldowns cooldowns;

    // The graph: the work on it, the growth each node came from, and what each growth says about nodes.
    private final Map<UUID, Node> nodes = new LinkedHashMap<>();
    private final Map<UUID, Expansion> grownBy = new LinkedHashMap<>();
    private final Map<UUID, List<Expansion>> feeding = new LinkedHashMap<>();
    private final Set<Expansion> live = new LinkedHashSet<>();
    private final Map<UUID, List<Touch>> touched = new LinkedHashMap<>();
    // The live makings by the goods they make, so a further edge for those goods can be added onto one.
    private final Map<ItemSpec, List<Expansion>> makings = new LinkedHashMap<>();

    // The goods handed off into a resident's pack, by the work that still needs them.
    private final Map<UUID, List<Claim>> pinned = new LinkedHashMap<>();

    // What was asked for, the growth answering each ask, and what is waiting to be grown.
    private final Map<UUID, Request> asked = new LinkedHashMap<>();
    private final Map<UUID, Expansion> answering = new LinkedHashMap<>();
    private final Set<UUID> sprouting = new LinkedHashSet<>();
    private final List<Edge> reopened = new ArrayList<>();
    private final Set<UUID> stowing = new LinkedHashSet<>();
    // Open edges into work that carries goods elsewhere rather than making them, by edge: fed only from stock or a
    // pack, though the goods are the very ones the work gives.
    private final Set<UUID> stowFresh = new LinkedHashSet<>();
    // Asked work its owner keeps off the plan, waiting on work it comes after that ended undone, until that work is
    // asked again.
    private final Map<UUID, Set<UUID>> awaiting = new LinkedHashMap<>();
    // Asked work the plan gave up for its owner since the weave was last shown, as its owner is to hear it.
    private final List<Gone> going = new ArrayList<>();

    // What the plan has to say about itself.
    private final Map<UUID, List<Gap>> gapsOf = new LinkedHashMap<>();
    private final List<Gap> endedGaps = new ArrayList<>();
    private final List<Gap> noted = new ArrayList<>();
    private final List<Refused> refused = new ArrayList<>();
    // Asked work the last try at growing found no resident fit for.
    private final Set<UUID> unfit = new HashSet<>();
    private final Set<Stash> looks = new LinkedHashSet<>();

    // This round.
    private final Set<UUID> grownNow = new LinkedHashSet<>();
    private final List<Expansion> madeNow = new ArrayList<>();
    private Situation now;
    private TransportPublication publication;
    private Crew crew = new Crew(List.of());
    private Costs costs;
    private Map<UUID, PlanTask> attempted = Map.of();
    private boolean wanted;
    private long taskSummaries;
    private List<Workshop> costedFor = List.of();
    private PackSizing sizing = new PackSizing(new Crew(List.of()));
    // What the crew could do when the plan was last checked against it, and whether it can do something else now.
    private List<Able> fitFor = List.of();
    private boolean refitting;
    private final Set<UUID> refitDue = new LinkedHashSet<>();
    // Residents the plan already has at work: in hand, or bound to work on the graph.
    private final Set<UUID> occupied = new LinkedHashSet<>();
    private final Map<Set<Stand>, Boolean> unreachable = new HashMap<>();
    // The walk bringing each input to each workshop, read once a round: stock taken during the round is not seen.
    private record WalkIn(ItemSpec input, WorldPos at) {
    }

    private final Map<WalkIn, Long> walkIns = new HashMap<>();

    private final Map<ReservationPool, PoolClaims> pools = new LinkedHashMap<>();
    private final Map<UUID, List<PoolClaims.Loan>> loans = new HashMap<>();
    private final Map<Edge, List<ReservationPool.Stored>> completedRuns = new LinkedHashMap<>();

    private Choice choosing;
    private Why lastWhy = Why.NO_SOURCE;
    private boolean weaving;
    private int fills;
    // What the last open edges before giving up fed, so the error names what kept growing.
    private final Map<String, Integer> lastFed = new LinkedHashMap<>();
    // How each try at joining an edge onto a live making went this weave.
    private final Map<String, Integer> joinsWent = new java.util.TreeMap<>();
    // Makings a join was tried on and failed this weave, by the edge they were first grown for; and whether a making
    // is being put back as it was, when nothing is joined onto anything.
    private final Set<UUID> joinFailed = new HashSet<>();
    private boolean restoring;
    private String madeShort = "";

    // What live growths bind, kept as they change; and what changed since the plan was last shown.
    private final Bonds bonds = new Bonds(new Bonds.Heard() {
        @Override
        public void ordered(Before order) {
            reordered.add(order);
        }

        @Override
        public void bound(UUID node) {
            dirty.add(node);
            refitDue.add(node);
        }
    });
    private final Set<UUID> dirty = new LinkedHashSet<>();
    private final Set<Before> reordered = new LinkedHashSet<>();

    // What was last shown of the plan, so that only what changes is shown again.
    private final Map<UUID, Vertex> shown = new HashMap<>();
    private final Map<UUID, Set<UUID>> shownGroups = new HashMap<>();
    private final Map<UUID, UUID> shownPins = new HashMap<>();
    private final Map<UUID, Set<UUID>> shownAfter = new HashMap<>();
    private final Map<UUID, Set<UUID>> shownBefore = new HashMap<>();
    private Set<UUID> shownHeld = Set.of();

    private final UUID colony;

    public Weaver(Claims claims, Cooldowns cooldowns) {
        this(claims, cooldowns, new UUID(0, 0));
    }

    public Weaver(Claims claims, Cooldowns cooldowns, UUID colony) {
        this.colony = colony;
        this.claims = claims;
        this.cooldowns = cooldowns;
        claims.watch(dirty::add);
    }

    // The core's own rules come before anything a plugin declares.
    private List<Refinement> rules() {
        List<Refinement> rules = new ArrayList<>(List.of(Haul.DELIVERIES, transport));
        rules.addAll(Refinements.all());
        return List.copyOf(rules);
    }

    private static final class Request {

        private final ResourceLocation owner;
        private final Grown work;
        // What it was asked together with: the asked nodes it comes after, and those one worker does with it.
        private final Set<UUID> after = new LinkedHashSet<>();
        private final List<Set<UUID>> together = new ArrayList<>();
        private Refining.Result pending;

        private Request(ResourceLocation owner, Grown work) {
            this.owner = owner;
            this.work = work;
        }
    }

    private record Gap(UUID hole, UUID consumer, ItemSpec spec, long count, Why why) {
    }

    private sealed interface Source {

        record Lot(Stock.Lot lot) implements Source {
        }

        record Offered(Costs.Offer offer) implements Source {
        }
    }

    // ---- the world this round ----

    // What a rule refining an intent this round is lent, without exposing the live graph.
    private Refinement.Context lent() {
        PackSizing sized = sizing;
        return new Refinement.Context() {
            @Override
            public Sizing sizing() {
                return sized;
            }

            @Override
            public Optional<Refinement.Planning> planning() {
                return Optional.of(new Refinement.Planning() {
                    @Override
                    public long now() {
                        return now.at();
                    }

                    @Override
                    public TransportPublication transports() {
                        return publication;
                    }

                    @Override
                    public TransportSnapshot snapshot(ItemSpec goods) {
                        List<TransportSnapshot.Carrier> carriers = new ArrayList<>();
                        for (Crew.Hand hand : crew.hands()) {
                            long room = hand.capacity(goods);
                            if (hand.takes(Vocations.of(Vocations.HAULING)) && room > 0) {
                                carriers.add(DirectTransport.carrier(hand.who(), hand.at(), goods, room,
                                    FolkwaysConfig.haulReachTicks()));
                            }
                        }
                        return new TransportSnapshot(now.at(), now.at(), now.ways().estimates(), carriers);
                    }
                });
            }
        };
    }

    List<PlanTask> attempted() {
        return List.copyOf(attempted.values());
    }

    private void remember(Grown work) {
        if (!wanted) {
            return;
        }
        for (Node node : work.nodes()) {
            attempted.put(node.id(), PlanTask.of(node.spec(), node.worker().orElse(null)));
            taskSummaries++;
        }
    }

    List<PlanInputs.Process> processes() {
        return costs == null ? List.of() : costs.processes();
    }

    public void know(Situation now, Crew crew) {
        know(now, crew, false);
    }

    void know(Situation now, Crew crew, boolean wanted) {
        this.wanted = wanted;
        this.now = now;
        // A hand can disappear from the crew without releasing its work.
        for (Crew.Hand hand : this.crew.hands()) {
            hand.holding().filter(node -> !hand.id().equals(holderOf(crew, node))).ifPresent(dirty::add);
        }
        for (Crew.Hand hand : crew.hands()) {
            hand.holding().filter(node -> !hand.id().equals(holderOf(this.crew, node))).ifPresent(dirty::add);
        }
        this.crew = crew;
        this.sizing = new PackSizing(crew);
        List<Able> able = crew.hands().stream().map(hand -> Able.of(hand, now.workshops())).toList();
        boolean changedAbility = !able.equals(fitFor);
        refitting |= changedAbility;
        fitFor = able;
        unreachable.clear();
        walkIns.clear();
        // What workshops offer is learned once for as long as the same workshops stand; the stock it is priced
        // against is read again every round.
        if (costs == null || changedAbility || !now.workshops().equals(costedFor)) {
            costedFor = now.workshops();
            costs = new Costs(costedFor.stream().filter(workshop -> workshop.bearer().isEmpty()
                || crew.hands().stream().anyMatch(hand -> workshop.bearer().filter(hand.id()::equals).isPresent()))
                .toList(), this::drawableAnywhere, claims::revision);
        } else {
            costs.restock();
        }
        costs.wanted(wanted);
        occupied.clear();
        for (Crew.Hand hand : crew.hands()) {
            hand.holding().ifPresent(held -> occupied.add(hand.id()));
        }
        for (UUID id : nodes.keySet()) {
            ownerOf(id).ifPresent(occupied::add);
        }
    }

    public WeaveDelta grow(boolean retry) {
        publication = now.transports().get();
        transport.begin(publication);
        if (weaving) {
            rollBack();
        }
        weaving = true;
        attempted = wanted ? new LinkedHashMap<>() : Map.of();
        endedGaps.clear();
        noted.clear();
        refused.clear();
        grownNow.clear();
        madeNow.clear();
        fills = 0;
        lastFed.clear();
        joinsWent.clear();
        joinFailed.clear();
        choosing = null;

        if (!crew.hands().isEmpty()) {
            List<UUID> due = List.copyOf(refitting ? nodes.keySet() : refitDue);
            refitDue.clear();
            refit(due);
            if (refitting) {
                refitting = false;
                reask();
            }
        }
        if (retry) {
            rechoose();
        }
        pools.values().forEach(pool -> pool.receive(claims));
        for (var finished : completedRuns.entrySet()) {
            Edge edge = finished.getKey();
            if (nodes.containsKey(edge.consumer())) {
                long left = deliverBooked(edge, finished.getValue());
                if (left > 0) {
                    reopened.addFirst(edge.covering(left));
                }
            }
        }
        completedRuns.clear();
        stowRunOutputs();
        repair();
        Set<UUID> retried = new LinkedHashSet<>();
        if (retry) {
            for (UUID id : asked.keySet()) {
                if (!standing(id) && !cooldowns.shut(gapped(id))) {
                    sprouting.add(id);
                    retried.add(id);
                }
            }
        }
        if (!crew.hands().isEmpty()) {
            List<UUID> due = new ArrayList<>(sprouting);
            sprouting.clear();
            // What comes after other asked work is grown once that work stands; until then it waits its turn.
            boolean grew = true;
            while (grew) {
                grew = false;
                for (java.util.Iterator<UUID> it = due.iterator(); it.hasNext(); ) {
                    UUID id = it.next();
                    if (!asked.containsKey(id) || standing(id)) {
                        it.remove();
                    } else if (comesNext(id)) {
                        it.remove();
                        seed(id);
                        grew = true;
                    }
                }
            }
            sprouting.addAll(due);
        }
        for (UUID id : retried) {
            if (standing(id)) {
                cooldowns.succeeded(gapped(id));
            } else if (asked.containsKey(id) && gapsOf.getOrDefault(id, List.of()).stream()
                    .anyMatch(gap -> !(gap.why() instanceof Why.Cooling))) {
                cooldowns.failed(gapped(id));
            }
        }
        recover(retry);
        weaving = false;
        if (fills > 2_000 && !joinsWent.isEmpty()) {
            LOGGER.info("weave fed {} open edges; joining like demands went {}", fills, joinsWent);
        }
        return publish();
    }

    private void rollBack() {
        LOGGER.warn("rolling back {} growths of a weave that did not finish", madeNow.size());
        for (Expansion grown : List.copyOf(madeNow).reversed()) {
            undo(grown, Ending.DROPPED);
        }
        madeNow.clear();
    }

    // ---- asking ----

    private static final Object GAPPED = new Object();

    private static Choice gapped(UUID id) {
        return new Choice(id, null, GAPPED);
    }

    private boolean standing(UUID id) {
        return answering.containsKey(id);
    }

    // Every node of the work is asked on its own, and the plan grows what feeds each. The order and the workers
    // binding them are kept between them for as long as both are asked.
    public void submit(ResourceLocation owner, Grown work) {
        Map<UUID, Request> fresh = new LinkedHashMap<>();
        for (Node node : work.nodes()) {
            Request was = asked.get(node.id());
            regrow(node.id(), Ending.REVOKED);
            Request request = new Request(owner, Grown.of(node));
            if (was != null) {
                request.after.addAll(was.after);
            }
            fresh.put(node.id(), request);
        }
        for (Before link : work.links()) {
            fresh.get(link.to()).after.add(link.from());
        }
        for (Before link : work.follows()) {
            fresh.get(link.to()).after.add(link.from());
        }
        for (Set<UUID> group : work.workerGroups()) {
            group.forEach(id -> fresh.get(id).together.add(group));
        }
        asked.putAll(fresh);
        sprouting.addAll(fresh.keySet());
        awaken();
    }

    // Work waiting on work that ended undone comes after it again once both are asked.
    private void awaken() {
        for (var waiting = awaiting.entrySet().iterator(); waiting.hasNext(); ) {
            var one = waiting.next();
            Request request = asked.get(one.getKey());
            if (request == null) {
                continue;
            }
            for (var before = one.getValue().iterator(); before.hasNext(); ) {
                UUID id = before.next();
                if (asked.containsKey(id)) {
                    request.after.add(id);
                    before.remove();
                }
            }
            if (one.getValue().isEmpty()) {
                waiting.remove();
                sprouting.add(one.getKey());
            }
        }
    }

    // Whether everything still asked that this comes after already stands, and nothing it waits on is unasked.
    private boolean comesNext(UUID id) {
        if (awaiting.containsKey(id)) {
            return false;
        }
        for (UUID before : asked.get(id).after) {
            if (asked.containsKey(before) && !standing(before)) {
                return false;
            }
        }
        return true;
    }

    public void withdraw(ResourceLocation owner, UUID node) {
        Request request = asked.get(node);
        if (request != null) {
            if (request.owner.equals(owner)) {
                drop(node, Ending.REVOKED);
            }
            return;
        }
        Node held = nodes.get(node);
        if (held != null && held.spec().owner().equals(owner)) {
            lose(node, Ending.REVOKED);
        }
    }

    // What was asked is done: whatever it was ordered before is free of it.
    private void unask(UUID id) {
        if (asked.remove(id) != null) {
            asked.values().forEach(request -> request.after.remove(id));
        }
        forgetAsking(id);
    }

    private void forgetAsking(UUID id) {
        sprouting.remove(id);
        gapsOf.remove(id);
        unfit.remove(id);
        awaiting.remove(id);
    }

    // What was asked goes, with everything grown for it, undone: what was ordered after it is left with an order no
    // work will meet, and its owner says what is left of it.
    private void drop(UUID id, Ending how) {
        List<UUID> next = new ArrayList<>();
        asked.forEach((other, request) -> {
            if (request.after.contains(id)) {
                next.add(other);
            }
        });
        regrow(id, how);
        if (asked.remove(id) != null) {
            unmet(id, how, next);
        }
        forgetAsking(id);
    }

    // Takes back what was grown for an ask, and with it what the ask was short of.
    private void regrow(UUID id, Ending how) {
        Expansion grown = answering.get(id);
        if (grown != null) {
            undo(grown, how);
        }
        if (weaving) {
            endedGaps.addAll(gapsOf.getOrDefault(id, List.of()));
        }
    }

    // Work ordered after `before`, which ended undone: each one's owner says what is left of the order. Work kept
    // waiting goes off the plan, asked still; work that fails is given up, and what comes after it is asked in turn.
    private void unmet(UUID before, Ending how, List<UUID> next) {
        for (UUID id : next) {
            Request request = asked.get(id);
            if (request == null || !request.after.remove(before)) {
                continue;
            }
            Node node = request.work.nodes().getFirst();
            Unmet answer;
            try {
                answer = node.unmet(before, how);
            } catch (RuntimeException | LinkageError broken) {
                LOGGER.error("node {} threw asked about {} ending as {}", id, before, how, broken);
                answer = Unmet.FAIL;
            }
            switch (answer) {
                case GO_ON -> { }
                case WAIT -> {
                    regrow(id, Ending.DROPPED);
                    awaiting.computeIfAbsent(id, key -> new LinkedHashSet<>()).add(before);
                    sprouting.add(id);
                }
                case FAIL -> {
                    Ending failed = new Ending.Failed(id, LaborRefusal.BEFORE_NOT_DONE);
                    going.add(new Gone(node, failed));
                    drop(id, failed);
                }
            }
        }
    }

    // ---- what the crew reports ----

    public void heldBy(UUID node, UUID worker, boolean carrying) {
        if (!nodes.containsKey(node)) {
            return;
        }
        dirty.add(node);
        Expansion grown = grownBy.get(node);
        grown.started = true;
        if (carrying) {
            pinCargo(node, worker, grown);
        }
    }

    public void released(UUID node) {
        dirty.add(node);
        refitDue.add(node);
    }

    public void finished(UUID node) {
        // Removing a step can split its group, so the remaining pieces each need checking.
        for (UUID member : together(node)) {
            if (!member.equals(node)) {
                refitDue.add(member);
            }
        }
        var drawn = loans.remove(node);
        if (drawn != null) {
            drawn.forEach(PoolClaims.Loan::run);
        }
        if (nodes.get(node) instanceof RunBoundary boundary) {
            Expansion run = grownBy.get(node);
            if (!run.isRequest()) {
                for (Edge edge : run.fed()) {
                    completedRuns.put(edge, boundary.reservations().storedSince(0));
                }
            }
            closePool(boundary);
            // A standalone run has no consumer edge. Its request settles with the node below.
            if (!run.isRequest()) {
                remove(run);
            }
        }
        unask(node);
        handOff(node);
        for (Expansion fed : List.copyOf(feeding.getOrDefault(node, List.of()))) {
            // A making shared with other consumers goes on for them: this one only had its share.
            if (fed.shared()) {
                detach(fed, node);
            } else {
                undo(fed, Ending.DONE);
            }
        }
        Expansion grown = grownBy.get(node);
        forget(node);
        if (grown != null) {
            grown.started = true;
            grown.nodes.remove(node);
            if (grown.nodes.isEmpty()) {
                grown.settle(Ending.DONE);
                if (grown.isRequest() || grown.fed().stream().noneMatch(edge -> nodes.containsKey(edge.consumer()))) {
                    remove(grown);
                }
            }
        }
    }

    public void failed(UUID node, RefusalKind why) {
        if (nodes.containsKey(node)) {
            Placement at = placement(node);
            looks.addAll(at.from());
            looks.addAll(at.into());
            at.draws().forEach(draw -> draw.from().ifPresent(looks::add));
        }
        lose(node, new Ending.Failed(node, why));
    }

    public void left(UUID resident) {
        for (Crew.Hand hand : crew.hands()) {
            if (resident.equals(hand.id())) {
                hand.holding().ifPresent(this::released);
            }
        }
        stowing.remove(resident);
        stowFresh.remove(resident);
        List<UUID> stranded = new ArrayList<>();
        for (UUID id : nodes.keySet()) {
            if (cargoOwnersOf(id).contains(resident) || resident.equals(pinOf(id))) {
                stranded.add(id);
            }
        }
        for (UUID id : stranded) {
            if (nodes.containsKey(id)) {
                lose(id, Ending.REVOKED);
            }
        }
        drop(resident, Ending.REVOKED);
    }

    public void stow(UUID resident) {
        stowing.add(resident);
        stowFresh.add(resident);
    }

    public Set<UUID> stowing() {
        return Set.copyOf(stowing);
    }

    public Set<Stash> looks() {
        Set<Stash> said = Set.copyOf(looks);
        looks.clear();
        return said;
    }

    public boolean asking() {
        return !asked.isEmpty() || !stowing.isEmpty();
    }

    public List<Refused> refused() {
        return List.copyOf(refused);
    }

    public List<Shortfall> shortfalls() {
        record By(ItemSpec what, UUID consumer, Why why) {
        }
        Map<By, List<Gap>> gathered = new LinkedHashMap<>();
        List<List<Gap>> all = new ArrayList<>(gapsOf.values());
        all.add(endedGaps);
        for (List<Gap> gaps : all) {
            for (Gap gap : gaps) {
                gathered.computeIfAbsent(new By(gap.spec(), gap.consumer(), gap.why()), key -> new ArrayList<>())
                    .add(gap);
            }
        }
        List<Shortfall> found = new ArrayList<>(gathered.size());
        gathered.forEach((by, gaps) -> {
            long missing = 0;
            List<UUID> holes = new ArrayList<>(gaps.size());
            for (Gap gap : gaps) {
                missing += gap.count();
                holes.add(gap.hole());
            }
            found.add(new Shortfall(by.what(), missing, by.consumer(), by.why(), holes));
        });
        return found;
    }

    // Asked work standing still for want of something the player can see to, told at the work: asked work no resident
    // is fit for, and asked work, or an edge into standing work, short of goods. Of an ask's gaps the first is told,
    // the one the others went short for in turn; an ask only resting after failing is told as it last failed.
    public List<Stall> stalls() {
        List<Stall> found = new ArrayList<>();
        for (UUID id : unfit) {
            placed(id, id).ifPresent(spec -> found.add(stall(spec, LaborRefusal.NO_ONE_FIT, Optional.empty())));
        }
        gapsOf.forEach((key, gaps) -> {
            for (Gap gap : gaps) {
                Optional<RefusalKind> why = told(gap.why());
                if (why.isPresent()) {
                    placed(key, gap.consumer()).ifPresent(spec ->
                        found.add(stall(spec, why.get(), Optional.of(gap.spec()))));
                    return;
                }
            }
        });
        return List.copyOf(new LinkedHashSet<>(found));
    }

    private static Optional<RefusalKind> told(Why why) {
        return switch (why) {
            case Why.NoSource ignored -> Optional.of(LaborRefusal.NO_SOURCE);
            case Why.NoRoom ignored -> Optional.of(LaborRefusal.NO_ROOM_FOR);
            case Why.NoWay ignored -> Optional.of(LaborRefusal.NO_WAY_FOR);
            case Why.NoHand ignored -> Optional.of(LaborRefusal.NO_ONE_MAKES);
            case Why.Turned ignored -> Optional.empty();
            case Why.Cooling ignored -> Optional.empty();
        };
    }

    // Where a stall is told: the asked work itself when the key is an ask, else the standing node the edge feeds. A
    // resident's own kit is fetched where the resident happens to stand, which is no place's work, and is not told.
    private Optional<NodeSpec> placed(UUID key, UUID consumer) {
        Request request = asked.get(key);
        Optional<NodeSpec> spec = request == null
            ? Optional.ofNullable(nodes.get(consumer)).map(Node::spec)
            : request.work.nodes().stream().filter(node -> node.id().equals(key)).findFirst()
                .or(() -> request.work.nodes().stream().findFirst())
                .map(Node::spec);
        return spec.filter(found -> !found.owner().equals(HaulWork.DOMAIN));
    }

    private static Stall stall(NodeSpec spec, RefusalKind why, Optional<ItemSpec> goods) {
        return new Stall(spec.site().where(), why, goods, spec.doing(), spec.about());
    }

    public void closed() {
        for (Expansion grown : List.copyOf(live)) {
            undo(grown, Ending.REVOKED);
        }
        nodes.clear();
        grownBy.clear();
        feeding.clear();
        makings.clear();
        live.clear();
        touched.clear();
        pinned.clear();
        asked.clear();
        answering.clear();
        sprouting.clear();
        reopened.clear();
        stowing.clear();
        stowFresh.clear();
        awaiting.clear();
        going.clear();
        gapsOf.clear();
        unfit.clear();
        endedGaps.clear();
        noted.clear();
        looks.clear();
        madeNow.clear();
        bonds.clear();
        dirty.clear();
        reordered.clear();
        shown.clear();
        shownGroups.clear();
        shownPins.clear();
        shownAfter.clear();
        shownBefore.clear();
        shownHeld = Set.of();
        completedRuns.clear();
        loans.clear();
        pools.clear();
        weaving = false;
        sizing = new PackSizing(new Crew(List.of()));
        fitFor = List.of();
        refitting = false;
        refitDue.clear();
        crew = new Crew(List.of());
    }

    // A handed claim names the exact consumer of the goods just put into the pack. Keep the claim on its growth
    // so taking that growth back releases both the goods and the work they bound.
    private void pinCargo(UUID producer, UUID worker, Expansion grown) {
        for (var it = grown.claims.listIterator(); it.hasNext(); ) {
            Claim claim = it.next();
            if (claim.on() instanceof Resource.Handed handed && producer.equals(handed.by())
                    && nodes.containsKey(claim.owner())) {
                Claim cargo = new Claim(new Resource.Cargo(worker, handed.spec()), claim.owner(), claim.amount());
                claims.drop(claim);
                claims.take(cargo);
                it.set(cargo);
                pinned.computeIfAbsent(cargo.owner(), key -> new ArrayList<>()).add(cargo);
                refitDue.add(cargo.owner());
            }
        }
    }

    // Goods put into a store are held for the next step until the stores are read again.
    private void handOff(UUID carrier) {
        Expansion grown = grownBy.get(carrier);
        if (grown == null) {
            return;
        }
        List<UUID> next = new ArrayList<>();
        for (Flow flow : grown.flows) {
            if (flow.after().filter(carrier::equals).isPresent() && nodes.containsKey(flow.to())
                    && !next.contains(flow.to())) {
                next.add(flow.to());
            }
        }
        for (Claim put : grown.claims) {
            if (!put.owner().equals(carrier) || !(put.on() instanceof Resource.Space(Stash where, ItemSpec spec))) {
                continue;
            }
            long left = put.amount();
            for (Claim draw : grown.claims) {
                if (left > 0 && next.contains(draw.owner()) && draw.on().equals(new Resource.Lot(where, spec))) {
                    long passed = Math.min(left, draw.amount());
                    claims.take(new Claim(put.on(), draw.owner(), passed));
                    left -= passed;
                }
            }
        }
    }

    // A node that cannot go on takes back the growth it came from, and whatever that growth fed is open again to be
    // fed some other way. What was asked feeds nothing, so nothing is left open: it goes.
    private void lose(UUID node, Ending how) {
        Expansion grown = grownBy.get(node);
        if (grown == null) {
            return;
        }
        if (grown.isRequest()) {
            // A child reports its own failure, but the submitter is listening at the request's boundary.
            if (how instanceof Ending.Failed && !node.equals(grown.asked)) {
                Request request = asked.get(grown.asked);
                if (request != null) {
                    going.add(new Gone(request.work.nodes().getFirst(), how));
                }
            }
            drop(grown.asked, how);
            return;
        }
        lose(grown, how);
    }

    private void lose(Expansion grown, Ending how) {
        for (Edge edge : grown.fed()) {
            if (nodes.containsKey(edge.consumer())) {
                reopened.add(edge);
            }
        }
        undo(grown, how);
    }

    // ---- growing ----

    // What one resident could do, as far as fitness reads it: whom, which trades and tools, how big a pack.
    private record Able(UUID id, ResourceLocation kind, int pack, Set<ResourceLocation> trades,
                        List<ResourceLocation> tools,
                        ToolPolicy toolRules, Set<UUID> carried) {

        static Able of(Crew.Hand hand, List<Workshop> workshops) {
            Set<ResourceLocation> trades = new LinkedHashSet<>();
            hand.licences().forEach(licence -> trades.add(licence.vocation().id()));
            return new Able(hand.id(), hand.who().kind(), hand.packCapacity(), trades,
                hand.tools().stream().map(tool -> BuiltInRegistries.ITEM.getKey(tool.getItem())).toList(),
                hand.toolRules(), workshops.stream()
                    .filter(workshop -> workshop.bearer().filter(hand.id()::equals).isPresent())
                    .map(Workshop::key).collect(java.util.stream.Collectors.toSet()));
        }
    }

    // Work in hand stays put. The rest goes back to its source or request if its owners conflict or it no
    // longer has a hand that can do it. An owner outside the crew keeps the work until they return.
    private void refit(Collection<UUID> due) {
        Set<UUID> seen = new HashSet<>();
        for (UUID id : due) {
            if (!nodes.containsKey(id) || seen.contains(id) || nodes.get(id) instanceof RunBoundary) {
                continue;
            }
            Set<UUID> group = together(id);
            seen.addAll(group);
            if (crew.hands().stream().anyMatch(hand -> hand.holding().filter(group::contains).isPresent())) {
                continue;
            }
            Set<UUID> owners = ownersOf(group);
            Fitness.Work work = work(group, peakLoad(group));
            if (owners.isEmpty() && Fitness.anyone(crew, work)) {
                continue;
            }
            if (owners.size() == 1) {
                UUID owner = owners.iterator().next();
                if (crew.hands().stream().filter(hand -> owner.equals(hand.id()))
                        .allMatch(hand -> Fitness.of(hand, work).isEmpty())) {
                    continue;
                }
            }
            Set<Expansion> grown = new LinkedHashSet<>();
            for (UUID member : group) {
                grown.add(grownBy.get(member));
            }
            Set<Expansion> fed = new LinkedHashSet<>();
            grown.stream().filter(one -> !one.isRequest()).forEach(fed::add);
            for (UUID member : group) {
                List<Claim> cargo = pinned.remove(member);
                dirty.add(member);
                if (cargo != null && !fed.isEmpty()) {
                    // A finished pickup may have left a feed with only its pack reservation still live.
                    for (Expansion feed : feeding.getOrDefault(member, List.of())) {
                        if (!Collections.disjoint(feed.claims, cargo)) {
                            fed.add(feed);
                        }
                    }
                }
            }
            if (!fed.isEmpty()) {
                for (Expansion one : fed) {
                    if (live.contains(one)) {
                        lose(one, Ending.DROPPED);
                    }
                }
                continue;
            }
            for (Expansion one : grown) {
                undo(one, Ending.DROPPED);
                if (asked.containsKey(one.asked)) {
                    sprouting.add(one.asked);
                }
            }
        }
    }

    // The crew can do other things than it could: what was asked and did not grow, for want of anyone fit or anything
    // they could fetch it with, is tried again at once rather than when its wait runs out.
    private void reask() {
        for (UUID id : asked.keySet()) {
            if (!standing(id)) {
                cooldowns.succeeded(new Choice(id, null, id));
                cooldowns.succeeded(gapped(id));
                sprouting.add(id);
            }
        }
    }

    // The edges reopened since the last round are fed again. A consumer that cannot be fed any more cannot go on
    // either, and is lost in turn. One whose source is only resting after failing waits open for it, and is fed
    // again once it opens.
    private void repair() {
        List<Edge> resting = new ArrayList<>();
        while (!reopened.isEmpty()) {
            Edge edge = reopened.removeFirst();
            if (!nodes.containsKey(edge.consumer())) {
                continue;
            }
            int mark = noted.size();
            gapsOf.remove(edge.id());
            if (fill(edge) > 0) {
                List<Gap> gaps = List.copyOf(noted.subList(mark, noted.size()));
                PoolClaims pool = grownBy.get(edge.consumer()).pool;
                if (pool == null && noted.getLast().why() instanceof Why.Cooling) {
                    gapsOf.put(edge.id(), gaps);
                    resting.add(edge);
                    continue;
                }
                endedGaps.addAll(gaps);
                RefusalKind why = told(noted.getLast().why()).orElse(LaborRefusal.NO_SOURCE);
                lose(edge.consumer(), pool == null ? Ending.DROPPED : new Ending.Failed(edge.consumer(), why));
            }
        }
        reopened.addAll(resting);
    }

    private void seed(UUID id) {
        Request request = asked.get(id);
        Choice choice = new Choice(id, null, id);
        Grown goal = request.work;
        if (cooldowns.shut(choice) || nodes.containsKey(id)) {
            return;
        }
        gapsOf.remove(id);
        unfit.remove(id);
        Refining.Result result;
        try {
            result = request.pending != null ? refining.reduce(request.pending) : refining.reduce(goal);
        } catch (RuntimeException broken) {
            LOGGER.error("invalid refinement for {}", id, broken);
            return;
        }
        request.pending = result.complete() ? null : result;
        if (!result.complete()) {
            return;
        }
        Grown work = result.graph();
        if (work.nodes().stream().anyMatch(node -> nodes.containsKey(node.id()))) {
            LOGGER.error("refinement for {} collided with existing work", id);
            return;
        }
        Optional<RefusalKind> turned = Expansion.take(result.holds());
        if (turned.isPresent()) {
            refused.add(new Refused(Optional.empty(), turned.get()));
            cooldowns.failed(choice);
            return;
        }
        int mark = noted.size();
        choosing = choice;
        Expansion grown = begin(new Expansion(id));
        grown.holds.addAll(result.holds());
        var scope = goal.nodes().getFirst() instanceof ScopedWork scoped ? scoped.reservations() : null;
        grown.pool = pools.get(scope);
        try {
            if (scope != null && grown.pool == null) {
                var failure = new Ending.Failed(id, LaborRefusal.NO_SOURCE);
                going.add(new Gone(goal.nodes().getFirst(), failure));
                drop(id, failure);
                return;
            }
            place(grown, work);
            grown.waitOn(work, Expansion.after(result.holds(), nodes.keySet()));
            askedWith(grown, request, work);
            if (work.nodes().size() == 1 && work.nodes().getFirst() instanceof RunBoundary boundary) {
                if (!bookPool(boundary)) {
                    undo(grown, Ending.DROPPED);
                }
                return;
            }
            if (!fitsAll(work)) {
                remember(work);
                refused.add(new Refused(Optional.empty(), LaborRefusal.NO_ONE_FIT));
                unfit.add(id);
                cooldowns.failed(choice);
                undo(grown, Ending.DROPPED);
                return;
            }
            if (!(landed(grown) && open(grown, Set.of()))) {
                remember(work);
                var gaps = List.copyOf(noted.subList(mark, noted.size()));
                if (grown.pool != null) {
                    Gap gap = gaps.isEmpty() ? null : gaps.getLast();
                    RefusalKind why = told(gap == null ? lastWhy : gap.why()).orElse(LaborRefusal.NO_SOURCE);
                    var failure = new Ending.Failed(gap == null ? id : gap.consumer(), why);
                    going.add(new Gone(goal.nodes().getFirst(), failure));
                    drop(id, failure);
                } else {
                    gapsOf.put(id, gaps);
                    undo(grown, Ending.DROPPED);
                }
            }
        } catch (RuntimeException | LinkageError broken) {
            undo(grown, Ending.DROPPED);
            throw broken;
        } finally {
            choosing = null;
        }
    }

    // What the work was asked together with holds for its growth: it starts after the asked work it comes after,
    // which ends where that work's own id does, and one worker does it with whatever of its group stands.
    private void askedWith(Expansion grown, Request request, Grown work) {
        Set<UUID> before = new LinkedHashSet<>();
        for (UUID id : request.after) {
            if (standing(id)) {
                before.add(id);
            }
        }
        grown.waitOn(work, before);
        for (Set<UUID> group : request.together) {
            Set<UUID> bound = new LinkedHashSet<>(grown.nodes);
            for (UUID member : group) {
                Expansion other = answering.get(member);
                if (other != null && other != grown) {
                    bound.addAll(other.nodes);
                }
            }
            grown.together(bound);
        }
    }

    // Goods put down into a store take room there, booked before anything is fetched for them.
    private boolean landed(Expansion grown) {
        for (UUID id : grown.nodes) {
            if (!(nodes.get(id) instanceof TransferNode put) || put.delivers().isEmpty()) {
                continue;
            }
            Stash into = put.delivers().get();
            Need goods = put.spec().needs().getFirst();
            if (roomFor(into, goods.spec(), put.ordered()) < goods.count()) {
                lastWhy = Why.NO_ROOM;
                noted.add(new Gap(id, id, goods.spec(), goods.count(), Why.NO_ROOM));
                return false;
            }
            book(grown, new Resource.Space(into, goods.spec()), id, goods.count());
        }
        return true;
    }

    // Every need of the work just grown is an open edge; all of them must be fed.
    private boolean open(Expansion grown, Set<ItemSpec> trail) {
        for (UUID id : List.copyOf(grown.nodes)) {
            Node node = nodes.get(id);
            if (node == null) {
                continue;
            }
            for (Edge edge : Edge.into(node, trail)) {
                if (fill(edge) > 0) {
                    return false;
                }
            }
        }
        return true;
    }

    // Grows sources for an open edge until it is fed, and returns what is still missing. What went wrong on the
    // way is kept only if the edge stays short.
    private long fill(Edge edge) {
        int mark = noted.size();
        Choice outer = choosing;
        long left;
        try {
            left = feed(edge);
        } finally {
            choosing = outer;
        }
        if (left <= 0) {
            noted.subList(mark, noted.size()).clear();
            return 0;
        }
        noted.add(new Gap(edge.id(), edge.consumer(), edge.spec(), left, lastWhy));
        return left;
    }

    private long feed(Edge edge) {
        lastWhy = Why.NO_SOURCE;
        int most = MOST_FILLS + FILLS_PER_ASK * asked.size();
        if (++fills > most) {
            if (fills == most + 1) {
                LOGGER.error("the plan gave up after {} open edges with {} nodes on the graph - growth is not"
                    + " converging; this is a bug, not a shortage; the last edges fed {}", most, nodes.size(),
                    lastFed.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(15).map(one -> one.getKey() + "=" + one.getValue()).toList());
            }
            return edge.count();
        }
        if (fills > most - 2_000) {
            Node consumer = nodes.get(edge.consumer());
            lastFed.merge((consumer == null ? "?" : consumer.getClass().getSimpleName()) + "<-"
                + edge.need().spec().describe() + "x" + edge.count() + " trail=" + edge.trail().size(), 1, Integer::sum);
        }
        if (edge.loops() || !nodes.containsKey(edge.consumer())) {
            return edge.count();
        }
        long whole = edge.count();
        PoolClaims pool = grownBy.get(edge.consumer()).pool;
        long left = pool == null ? whole : whole - borrow(pool, edge, whole);
        left -= fromPack(edge, left);
        while (left > 0 && !publication.entries().isEmpty()) {
            long carried = fromPack(edge, left);
            if (carried <= 0) {
                break;
            }
            left -= carried;
        }
        if (left > 0 && !restoring && joinable(edge.spec())) {
            List<Source> ranked = sources(edge, left, whole);
            if (!ranked.isEmpty() && ranked.getFirst() instanceof Source.Offered(Costs.Offer cheapest)) {
                left -= joined(edge, cheapest, left);
            } else {
                joinsWent.merge(ranked.isEmpty() ? "no source" : "a lot is cheaper", 1, Integer::sum);
            }
        }
        Why why = Why.NO_SOURCE;
        boolean grew = left > 0;
        while (left > 0 && grew) {
            grew = false;
            for (Source source : sources(edge, left, whole)) {
                long got = switch (source) {
                    case Source.Lot(Stock.Lot lot) -> fromStore(edge, lot, left);
                    case Source.Offered(Costs.Offer offer) -> make(edge, left, List.of(), offer);
                };
                if (got > 0) {
                    left -= got;
                    grew = true;
                    if (left <= 0) {
                        break;
                    }
                } else if (!(lastWhy instanceof Why.NoSource)) {
                    why = lastWhy;
                }
            }
        }
        lastWhy = why;
        return left;
    }

    // Whether some live making makes goods the edge would take.
    private boolean joinable(ItemSpec wanted) {
        for (ItemSpec made : makings.keySet()) {
            if (admits(wanted, made)) {
                return true;
            }
        }
        return false;
    }

    // Every way the edge could be fed, cheapest first: lots in stock cost the walk to the consumer, workshop offers
    // their work and inputs besides, the walk bringing those inputs to the workshop, and the walk on to the consumer. Offers are asked for again with what is left each time round.
    // A workshop's alike offers stand together, at the cost of the cheapest of them, and among themselves the one
    // nearest the crew as it stands comes first: the walk to it from whoever would do it counts as well.
    private List<Source> sources(Edge edge, long left, long whole) {
        record Ranked(Source source, long cost, int kind, long plenty, String name, int group, long byHand) {
        }
        Node consumer = nodes.get(edge.consumer());
        WorkSite near = consumer.spec().site();
        List<Ranked> ranked = new ArrayList<>();
        for (Stock.Lot lot : now.stock().lotsOf(edge.spec())) {
            if (drawable(lot.where(), lot.concrete()) > 0 && feeds(lot.where(), edge.consumer(), lot.concrete())) {
                long cost = !edge.carried() && !reaching(edge.consumer(), lot.where()).isEmpty() ? 0
                    : carriedTo(edge, lot.where().pos(), now.stands().around(lot.where(), Operation.TAKE),
                    TransportEndpoint.Form.INVENTORY, lot.concrete(),
                    Math.min(left, drawable(lot.where(), lot.concrete())));
                if (cost == Costs.UNREACHABLE) {
                    continue;
                }
                ranked.add(new Ranked(new Source.Lot(lot), cost, 0,
                    now.stock().available(lot.concrete()), lot.where().toString(), ranked.size(), cost));
            }
        }
        Map<UUID, Integer> groups = new HashMap<>();
        Map<UUID, Long> cheapest = new HashMap<>();
        List<WorldPos> hands = null;
        if (consumer.permitsProduction(edge.spec()) && costs.reachable(edge.spec(), near, edge.trail())) {
            for (Costs.Offer offer : costs.offers(Produce.of(OWNER, near, edge.spec(), left,
                    sizing.batching(whole)))) {
                Grown made = offer.change().replacement();
                if (yieldOf(made, edge.spec()) <= 0) {
                    continue;
                }
                long cost = priced(edge, near, offer);
                if (cost == Costs.UNREACHABLE) {
                    continue;
                }
                int group = ranked.size();
                long byHand = cost;
                if (offer.from().alike()) {
                    hands = hands == null ? handsFor(edge.consumer()) : hands;
                    group = groups.computeIfAbsent(offer.from().key(), key -> ranked.size());
                    cheapest.merge(offer.from().key(), cost, Math::min);
                    byHand = Costs.plus(cost, walkTo(hands, made));
                }
                ranked.add(new Ranked(new Source.Offered(offer), cost, 1, 0, "", group, byHand));
            }
        }
        if (!groups.isEmpty()) {
            ranked.replaceAll(one -> one.source() instanceof Source.Offered(Costs.Offer offer) && offer.from().alike()
                ? new Ranked(one.source(), cheapest.get(offer.from().key()), one.kind(), one.plenty(), one.name(),
                    one.group(), one.byHand())
                : one);
        }
        ranked.sort(Comparator.comparingLong(Ranked::cost).thenComparingInt(Ranked::kind)
            .thenComparing(Comparator.comparingLong(Ranked::plenty).reversed()).thenComparing(Ranked::name)
            .thenComparingInt(Ranked::group).thenComparingLong(Ranked::byHand));
        return ranked.stream().map(Ranked::source).toList();
    }

    // What an offer would cost the edge: its work and inputs, the walk bringing those to it, and the way on.
    private long priced(Edge edge, WorkSite near, Costs.Offer offer) {
        return priced(edge, near, offer, waitFor(offer));
    }

    private long priced(Edge edge, WorkSite near, Costs.Offer offer, long waiting) {
        Grown made = offer.change().replacement();
        long cost = costs.of(made, near, edge.upstream());
        return cost == Costs.UNREACHABLE ? cost
            : Costs.plus(Costs.plus(Costs.plus(cost, waiting), walkIn(made)), deliveredFrom(made, edge, near));
    }

    // The way from where an offer's goods come out on to the consumer: the nearest straight line until a transport
    // is published, then the cheapest route known for the whole yield, straight there or by one trunk.
    private long deliveredFrom(Grown made, Edge edge, WorkSite near) {
        long straight = walkFrom(made, edge.spec(), near);
        if (publication.entries().isEmpty() || !nodes.containsKey(edge.consumer())) {
            return straight;
        }
        long best = Costs.UNREACHABLE;
        boolean any = false;
        for (Node node : made.nodes()) {
            for (Amount gives : node.spec().gives()) {
                if (admits(edge.spec(), gives.spec())) {
                    any = true;
                    WorldPos at = node.spec().site().where();
                    // Goods leave with the worker beside the station, not from inside its solid block.
                    Set<Stand> loading = node.spec().stances() instanceof Stances.Cells
                        ? WorkStances.cellsOf(node.spec().stances()) : Set.of(new Stand(at));
                    best = Math.min(best, carriedTo(edge, at, loading, TransportEndpoint.Form.PACK,
                        gives.spec(), Math.min(edge.count(), gives.count())));
                }
            }
        }
        return any ? best : straight;
    }

    // What carrying `count` goods from `from` to the edge's consumer would cost. By straight line while no transport
    // is published; otherwise the cheapest known route, keeping the straight line while no route is known yet.
    private long carriedTo(Edge edge, WorldPos from, Set<Stand> loading, TransportEndpoint.Form form,
                           ItemSpec goods, long count) {
        WorkSite near = nodes.get(edge.consumer()).spec().site();
        long straight = Transfer.walk(from, near);
        Set<Stand> unloading = standsOf(edge.consumer());
        TransportIntent intent = TransportIntent.of(Routing.endpoint("source", from, loading, form),
            Routing.endpoint("destination", near.where(),
                unloading == null ? Set.of(new Stand(near.where())) : unloading, TransportEndpoint.Form.PACK),
            goods, count, now.at());
        return refining.estimate(intent).orElse(straight);
    }

    // Where whoever could do work for the consumer stands: whoever it is already bound to, or else anyone.
    private List<WorldPos> handsFor(UUID consumer) {
        Set<UUID> bound = ownersOf(together(consumer));
        nodes.get(consumer).worker().ifPresent(bound::add);
        List<WorldPos> at = new ArrayList<>();
        for (Crew.Hand hand : crew.hands()) {
            if (bound.isEmpty() || bound.contains(hand.id())) {
                at.add(hand.at());
            }
        }
        return at;
    }

    // The walk to where the work starts from the nearest of `hands`, by straight lines.
    private static long walkTo(List<WorldPos> hands, Grown made) {
        Set<UUID> follow = new HashSet<>();
        made.links().forEach(link -> follow.add(link.to()));
        long nearest = Long.MAX_VALUE;
        for (Node node : made.nodes()) {
            if (follow.contains(node.id())) {
                continue;
            }
            for (WorldPos hand : hands) {
                nearest = Math.min(nearest, node.worker().isPresent()
                    && node.spec().site() instanceof WorkSite.AtEntity entity
                    && node.worker().get().equals(entity.entity()) ? 0 : Transfer.walk(hand, node.spec().site()));
            }
        }
        return nearest == Long.MAX_VALUE ? 0 : nearest;
    }

    // A way to goods taken from a workshop's alike offers is weighed again against where the crew stands now, while
    // none of it is begun and every step of it may still be swapped: when another of the workshop's ways would bring
    // the goods clearly sooner, the one taken goes and its edge is fed afresh, which takes the nearer way.
    private void rechoose() {
        for (Expansion grown : List.copyOf(live)) {
            if (!live.contains(grown) || grown.making == null || !grown.making.offer().from().alike()
                    || grown.serves == null || !grown.also.isEmpty() || !nodes.containsKey(grown.serves.consumer())
                    || !untouched(grown) || !grown.nodes.stream().allMatch(id -> nodes.get(id).replannable())) {
                continue;
            }
            Edge edge = grown.serves;
            Workshop from = grown.making.offer().from();
            WorkSite near = nodes.get(edge.consumer()).spec().site();
            List<WorldPos> hands = handsFor(edge.consumer());
            Grown taken = grown.making.offer().change().replacement();
            long current = Costs.plus(priced(edge, near, grown.making.offer()), walkTo(hands, taken));
            long best = Costs.UNREACHABLE;
            for (Costs.Offer offer : costs.offers(Produce.of(OWNER, near, edge.spec(), Math.max(1, grown.covers),
                    sizing.batching(edge.count())))) {
                Grown made = offer.change().replacement();
                if (offer.from().key().equals(from.key()) && yieldOf(made, edge.spec()) > 0) {
                    best = Math.min(best, Costs.plus(priced(edge, near, offer), walkTo(hands, made)));
                }
            }
            if (current != Costs.UNREACHABLE && best != Costs.UNREACHABLE
                    && best + Math.max(SWITCH_TICKS, current / SWITCH_SHARE) < current) {
                reopened.add(edge);
                undo(grown, Ending.DROPPED);
            }
        }
    }

    // Without published transport, pack goods stay with the consumer's worker. Routed goods keep their carrier
    // as a source constraint and the consumer's binding as a separate destination constraint.
    private long fromPack(Edge edge, long count) {
        if (count <= 0) {
            return 0;
        }
        Node consumer = nodes.get(edge.consumer());
        boolean routed = !publication.entries().isEmpty();
        if (!routed && consumer instanceof Receipt) {
            return 0;
        }
        // Whoever the consumer's work is already bound to - through any step it must be done together with -
        // is the only one whose pack can feed it: goods from another's pack would split the work between two.
        Set<UUID> bound = ownersOf(together(consumer.id()));
        consumer.worker().ifPresent(bound::add);
        if (bound.size() > 1) {
            return 0;
        }
        // The goods are in the pack already: what it takes is fitness for all of the consumer's group.
        Fitness.Work work = work(together(edge.consumer()), 0);
        for (Crew.Hand hand : crew.hands()) {
            if (occupied.contains(hand.id()) || !routed && Fitness.of(hand, work).isPresent()) {
                continue;
            }
            Map<ItemSpec, Long> skipped = new LinkedHashMap<>();
            List<ItemStack> taken = new ArrayList<>();
            long served = 0;
            for (ItemStack stack : hand.cargo()) {
                if (!Goods.matches(stack, edge.spec()) || served == count) {
                    continue;
                }
                ItemSpec concrete = Goods.specOf(stack);
                long used = skipped.computeIfAbsent(concrete,
                    spec -> claims.taken(new Resource.Cargo(hand.id(), spec)));
                long skip = Math.min(used, stack.getCount());
                skipped.put(concrete, used - skip);
                int take = (int) Math.min(count - served, stack.getCount() - skip);
                if (take > 0) {
                    taken.add(stack.copyWithCount(take));
                    served += take;
                }
            }
            if (served == 0) {
                continue;
            }
            Expansion grown = begin(new Expansion(edge));
            try {
                grown.covers = transfer(grown, new From.Carried(hand.id(), taken), new To.Feeding(edge),
                    edge.spec(), served);
            } catch (RuntimeException | LinkageError broken) {
                undo(grown, Ending.DROPPED);
                throw broken;
            }
            if (grown.covers > 0) {
                return grown.covers;
            }
            undo(grown, Ending.DROPPED);
        }
        return 0;
    }

    private boolean bookPool(RunBoundary boundary) {
        var budget = boundary.reservations();
        PoolClaims pool = new PoolClaims(budget);
        pools.put(budget, pool);
        pool.receive(claims);
        var needs = budget.remaining();
        for (int index = 0; index < needs.size(); index++) {
            Need need = needs.get(index);
            long left = need.count();
            for (Stock.Lot lot : now.stock().lotsOf(need.spec())) {
                long count = Math.min(left, drawable(lot.where(), lot.concrete()));
                pool.adjust(claims, new PoolClaims.Input(index, new Resource.Lot(lot.where(), lot.concrete())), count);
                left -= count;
                if (left == 0) {
                    break;
                }
            }
            if (left > 0) {
                return false;
            }
        }
        return true;
    }

    private void closePool(RunBoundary boundary) {
        PoolClaims pool = pools.remove(boundary.reservations());
        if (pool != null) {
            pool.close(claims);
            for (Expansion hauling : List.copyOf(pool.hauling.values())) {
                undo(hauling, Ending.REVOKED);
            }
        }
    }

    private long borrow(PoolClaims pool, Edge edge, long count) {
        long left = count;
        for (var key : List.copyOf(pool.inputs.keySet())) {
            Resource.Lot lot = key.lot();
            if (!Goods.matches(Goods.display(lot.spec()), edge.spec())
                    || !feeds(lot.where(), edge.consumer(), lot.spec())) {
                continue;
            }
            while (left > 0) {
                Claim held = pool.inputs.get(key);
                if (held == null) {
                    break;
                }
                long asked = Math.min(left, held.amount());
                Expansion grown = begin(new Expansion(edge));
                pool.adjust(claims, key, -asked);
                long assigned = 0;
                long moved;
                try {
                    moved = transfer(grown, new From.Stocked(lot.where()), new To.Feeding(edge), lot.spec(), asked);
                    if (moved > 0) {
                        for (Claim claim : grown.claims) {
                            if (claim.on().equals(lot)) {
                                var loan = new PoolClaims.Loan(pool, key, claim);
                                grown.loans.add(loan);
                                loans.computeIfAbsent(claim.owner(), id -> new ArrayList<>()).add(loan);
                                assigned += claim.amount();
                            }
                        }
                    }
                } catch (RuntimeException | LinkageError broken) {
                    undo(grown, Ending.DROPPED);
                    throw broken;
                } finally {
                    pool.adjust(claims, key, asked - assigned);
                }
                if (moved <= 0) {
                    undo(grown, Ending.DROPPED);
                    break;
                }
                grown.covers = moved;
                left -= moved;
            }
        }
        return count - left;
    }

    private long deliverBooked(Edge edge, List<ReservationPool.Stored> stored) {
        long left = edge.count();
        for (var put : stored) {
            if (left == 0) {
                break;
            }
            if (!admits(edge.spec(), put.spec()) || !feeds(put.where(), edge.consumer(), put.spec())) {
                continue;
            }
            Expansion grown = begin(new Expansion(edge));
            long moved = transfer(grown, new From.LeftBy(List.of(), put.where()),
                new To.Feeding(edge), put.spec(), Math.min(left, put.count()));
            if (moved > 0) {
                grown.covers = moved;
                left -= moved;
            } else {
                undo(grown, Ending.DROPPED);
            }
        }
        return left;
    }

    private void stowRunOutputs() {
        for (PoolClaims pool : pools.values()) {
            var carried = pool.budget.carried();
            // Protect all goods waiting to be stowed before choosing routes for any of them.
            for (var cargo : carried) {
                if (!pool.hauling.containsKey(cargo.id()) && !pool.packed.containsKey(cargo.id())) {
                    Claim hold = new Claim(new Resource.Cargo(cargo.resident(), cargo.spec()), cargo.id(), cargo.count());
                    pool.packed.put(cargo.id(), hold);
                    claims.take(hold);
                }
            }
            for (var cargo : carried) {
                if (!pool.hauling.containsKey(cargo.id())) {
                    stowRunOutput(pool, cargo);
                }
            }
        }
    }

    private void stowRunOutput(PoolClaims pool, ReservationPool.Carried cargo) {
        var hand = crew.hands().stream().filter(one -> one.id().equals(cargo.resident())).findFirst().orElse(null);
        if (hand == null) {
            return;
        }
        List<RecoveryStore> stores = new ArrayList<>();
        for (Stash store : now.stock().stashes()) {
            Set<Stand> at = now.stands().around(store, Operation.PUT);
            if (now.stock().stores(store) && now.stock().direct(store) && !at.isEmpty()
                    && roomFor(store, cargo.spec()) >= cargo.count() && !cutOff(store, at)) {
                stores.add(new RecoveryStore(store, at, walkAtLeast(hand.at(), store.pos())));
            }
        }
        List<RecoveryStore> ordered = puttingAway(stores, cargo.spec());
        ordered.sort(Comparator.comparing(store -> pool.consumers.stream().noneMatch(
            consumer -> nodes.containsKey(consumer) && feeds(store.stash(), consumer, cargo.spec()))));
        for (RecoveryStore into : ordered) {
            Claim hold = pool.packed.remove(cargo.id());
            claims.drop(hold);
            Expansion grown = begin(new Expansion(cargo.id()));
            grown.pool = pool;
            grown.stowing = cargo.id();
            pool.hauling.put(cargo.id(), grown);
            ItemStack stack = Goods.display(cargo.spec()).copyWithCount(cargo.count());
            var from = new From.Carried(cargo.resident(), List.of(stack));
            var to = new To.Stored(into.stash());
            if (transfer(grown, from, to, cargo.spec(), cargo.count()) == cargo.count()) {
                return;
            }
            undo(grown, Ending.DROPPED);
            pool.packed.put(cargo.id(), hold);
            claims.take(hold);
        }
    }

    // A lot in a store: each carry of it is its own growth, so a failed fetch takes back only what it was bringing.
    private long fromStore(Edge edge, Stock.Lot lot, long left) {
        long can = Math.min(left, drawable(lot.where(), lot.concrete()));
        if (can <= 0) {
            return 0;
        }
        Choice choice = new Choice(edge.id(), lot.concrete(), lot.where());
        if (cooldowns.shut(choice)) {
            lastWhy = new Why.Cooling(choice);
            return 0;
        }
        choosing = choice;
        long got = 0;
        while (got < can) {
            Expansion grown = begin(new Expansion(edge));
            long moved;
            try {
                moved = transfer(grown, new From.Stocked(lot.where()), new To.Feeding(edge),
                    lot.concrete(), can - got);
            } catch (RuntimeException | LinkageError broken) {
                undo(grown, Ending.DROPPED);
                throw broken;
            }
            if (moved <= 0) {
                undo(grown, Ending.DROPPED);
                break;
            }
            grown.covers = moved;
            got += moved;
        }
        return got;
    }

    // A workshop's offer taken for one edge first and then for every further edge in `also`, each fed whole from the
    // one making. Return how much of the primary edge this growth takes responsibility for.
    private long make(Edge edge, long left, List<Edge> also, Costs.Offer offer) {
        Refinement.Change change = offer.change();
        ItemSpec made = concreteOf(change.replacement(), edge.spec());
        Choice choice = new Choice(edge.id(), made, offer.from().key());
        if (cooldowns.shut(choice)) {
            lastWhy = new Why.Cooling(choice);
            madeShort = "cooling";
            return 0;
        }
        Refining.Result expanded;
        try {
            expanded = refining.expand(change.replacement()).orElse(null);
        } catch (RuntimeException | LinkageError broken) {
            LOGGER.error("workshop {} answered with work that does not refine", offer.from().key(), broken);
            return 0;
        }
        if (expanded == null || expanded.graph().nodes().stream().anyMatch(node -> nodes.containsKey(node.id()))) {
            return 0;
        }
        Grown work = expanded.graph();
        List<Hold> holds = new ArrayList<>(change.holds());
        holds.addAll(expanded.holds());
        Optional<RefusalKind> turned = Expansion.take(holds);
        Set<UUID> after = Expansion.after(holds, nodes.keySet());
        // A rejected batch join can restore an old offer, including its node ids. Other live growths may
        // still order themselves after those ids. Rebooking at the back must not make the restored maker
        // wait on one of its own successors, even when its consumer has no path to that successor yet.
        if (turned.isEmpty() && (work.nodes().stream().anyMatch(node -> bonds.reaches(node.id(), after))
                || bonds.reaches(edge.consumer(), after)
                || also.stream().anyMatch(further -> bonds.reaches(further.consumer(), after)))) {
            Expansion.release(holds, Ending.DROPPED);
            turned = Optional.of(LaborRefusal.WAITS_ON_ITSELF);
        }
        if (turned.isPresent()) {
            refused.add(new Refused(Optional.of(edge.spec()), turned.get()));
            cooldowns.failed(choice);
            lastWhy = new Why.Turned(turned.get());
            return 0;
        }
        choosing = choice;
        Expansion grown = begin(new Expansion(edge));
        grown.holds.addAll(holds);
        try {
            place(grown, work);
            grown.waitOn(work, after);
            if (work.nodes().size() == 1 && work.nodes().getFirst() instanceof RunBoundary boundary) {
                if (!bookPool(boundary)) {
                    undo(grown, Ending.DROPPED);
                    lastWhy = Why.NO_SOURCE;
                    return 0;
                }
                // Keep the outstanding demand with this run. Its hold is exclusive, so the next run can
                // only be booked after completion reopens whatever the stored output did not supply.
                pools.get(boundary.reservations()).consumers.add(edge.consumer());
                grown.covers = left;
                grown.link(new Before(boundary.id(), edge.consumer()));
                return left;
            }
            for (Node node : work.nodes()) {
                var draw = node.stockDraw().orElse(null);
                if (draw == null) {
                    continue;
                }
                var goods = draw.goods();
                long stocked = now.stock().stockForWorkshop(draw.source(), goods.spec());
                if (unclaimed(draw.source(), goods.spec(), stocked) < goods.count()) {
                    undo(grown, Ending.DROPPED);
                    madeShort = "source stock already spoken for";
                    return 0;
                }
                book(grown, new Resource.Lot(draw.source(), goods.spec()), node.id(), goods.count());
            }
            if (!fitsAll(work)) {
                remember(work);
                undo(grown, Ending.DROPPED);
                lastWhy = Why.NO_HAND;
                madeShort = "no hand";
                return 0;
            }
            long yield = yieldOf(work, made);
            long covers = Math.min(left, yield);
            UUID maker = makerOf(work, made);
            boolean shared = !also.isEmpty();
            if (maker == null
                    || transfer(grown, new From.Made(maker, yield, shared), new To.Feeding(edge), made,
                        covers) < covers) {
                undo(grown, Ending.DROPPED);
                madeShort = maker == null ? "no maker" : "carry to the first consumer: " + lastWhy;
                return 0;
            }
            grown.covers = covers;
            Left lying = leftIn(grown, maker);
            Stash out = lying == null ? null : lying.out();
            for (Edge further : also) {
                attach(grown, further);
                if (lying == null || transfer(grown, new From.LeftBy(lying.suppliers(), lying.out()),
                        new To.Feeding(further), made, further.count()) < further.count()) {
                    undo(grown, Ending.DROPPED);
                    madeShort = out == null ? "no store beside the maker: " + grown.flows.stream()
                        .filter(flow -> flow.after().filter(maker::equals).isPresent())
                        .map(flow -> String.valueOf(nodes.get(flow.to()) == null ? "?"
                            : nodes.get(flow.to()).getClass().getSimpleName()))
                        .toList() + " touches " + grown.touches.stream().filter(t -> t.into() != null)
                        .map(t -> nodes.get(t.node()) == null ? "?" : nodes.get(t.node()).getClass().getSimpleName())
                        .toList() : "carry to a further consumer: " + lastWhy;
                    return 0;
                }
            }
            int mark = noted.size();
            if (!open(grown, edge.upstream())) {
                madeShort = "inputs: " + (noted.size() > mark ? noted.getLast().spec().describe() + " x"
                    + noted.getLast().count() + " " + noted.getLast().why() : lastWhy);
                undo(grown, Ending.DROPPED);
                return 0;
            }
            grown.making = new Expansion.Making(made, maker, out, offer);
            makings.computeIfAbsent(made, key -> new ArrayList<>()).add(grown);
            return covers;
        } catch (RuntimeException | LinkageError broken) {
            undo(grown, Ending.DROPPED);
            throw broken;
        }
    }

    // Where what the maker makes is left for its consumers, and whose work leaves it there.
    private record Left(Stash out, List<UUID> suppliers) {
    }

    // The store this growth leaves what its maker makes in, if it has one: beside the maker, or one the made goods are
    // carried on into.
    private Left leftIn(Expansion grown, UUID maker) {
        for (Touch touch : grown.touches) {
            if (touch.node().equals(maker) && touch.into() != null) {
                return new Left(touch.into(), List.of(maker));
            }
        }
        Stash out = null;
        List<UUID> puts = new ArrayList<>();
        for (Flow flow : grown.flows) {
            if (flow.after().filter(maker::equals).isEmpty() || puts.contains(flow.to())) {
                continue;
            }
            for (Touch touch : grown.touches) {
                if (touch.node().equals(flow.to()) && touch.into() != null
                        && (out == null || out.equals(touch.into()))) {
                    out = touch.into();
                    puts.add(flow.to());
                    break;
                }
            }
        }
        return out == null ? null : new Left(out, puts);
    }

    // An edge wanting what a live making already makes, at the workshop the edge would take it from anyway, is added
    // onto that making: the workshop is asked for the whole again, the making is laid afresh to feed every edge it
    // fed and this one, each from the store beside the maker, and its own needs are fed in turn - so like demands
    // share one way of making rather than each growing its own. A making is joined only while none of its work, or of
    // what feeds it, is done or in hand, only when the workshop makes enough in one go, and only when the two made
    // together come no dearer than made apart; otherwise the edge is fed on its own. Returns what it got: all of
    // `left`, or nothing.
    //
    // Work the workshop only waits through - a cooker cooking, or a load's turn behind the batches booked before it -
    // is where joining costs: the two loads cook one after the other in one cooker, where apart they may cook side by
    // side in two. Such a workshop is asked for the whole only once the making is taken off, so the booking it holds
    // is not counted as a wait in front of itself.
    private long joined(Edge edge, Costs.Offer cheapest, long left) {
        ItemSpec made = concreteOf(cheapest.change().replacement(), edge.spec());
        WorkSite wants = nodes.get(edge.consumer()).spec().site();
        // The making at the workshop the edge would choose first, then the nearest others, a few at most.
        List<Expansion> candidates = new ArrayList<>(makings.getOrDefault(made, List.of()));
        candidates.sort(Comparator.comparingInt((Expansion one) ->
                one.making.offer().from().key().equals(cheapest.from().key()) ? 0 : 1)
            .thenComparingLong(one -> Transfer.walk(one.making.offer().from().site().where(), wants)));
        int tried = 0;
        for (Expansion into : candidates) {
            Expansion.Making making = into.making;
            if (!live.contains(into)) {
                List<Expansion> same = makings.get(made);
                if (same != null) {
                    same.remove(into);
                }
                joinsWent.merge("skipped: gone", 1, Integer::sum);
                continue;
            }
            String skip = into.full ? "full" : joinFailed.contains(into.serves.id()) ? "failed before"
                : !into.serves.trail().equals(edge.trail()) ? "other trail"
                : into.consumers().contains(edge.consumer()) ? "same consumer" : !untouched(into) ? "in hand"
                : bonds.reaches(edge.consumer(), into.nodes) ? "would loop" : null;
            if (skip != null) {
                joinsWent.merge("skipped: " + skip, 1, Integer::sum);
                continue;
            }
            if (++tried > MOST_JOINS_TRIED) {
                break;
            }
            List<Edge> fed = into.fed();
            if (fed.isEmpty() || !fed.getFirst().consumer().equals(into.serves.consumer())) {
                continue;
            }
            long total = left;
            for (Edge one : fed) {
                total += one.count();
            }
            WorkSite near = nodes.get(into.serves.consumer()).spec().site();
            Grown before = making.offer().change().replacement();
            // The server can finish queued work while this lane plans. Use one waiting snapshot
            // per offer so the same wait is included in, and removed from, its quoted cost.
            long beforeWait = waitFor(making.offer());
            long otherWait = waitFor(cheapest);
            long beforeIdle = Costs.plus(idle(before), beforeWait);
            long otherIdle = Costs.plus(idle(cheapest.change().replacement()), otherWait);
            long apart = apart(priced(fed.getFirst(), near, making.offer(), beforeWait), beforeIdle,
                priced(edge, wants, cheapest, otherWait), otherIdle);
            boolean waits = beforeIdle > 0 || !making.offer().change().holds().isEmpty();
            Costs.Offer bigger = waits ? null : bigger(near, made, total, making.offer().from());
            if (!waits && bigger == null) {
                into.full = true;
                List<Expansion> same = makings.get(made);
                if (same != null) {
                    same.remove(into);
                }
                tried--;
                joinsWent.merge("workshop makes too few at once", 1, Integer::sum);
                continue;
            }
            int waiting = reopened.size();
            undo(into, Ending.DROPPED);
            if (waits) {
                bigger = bigger(near, made, total, making.offer().from());
            }
            String refused = bigger == null ? "workshop makes too few at once"
                : priced(fed.getFirst(), near, bigger) > apart
                    ? "dearer together than apart" : null;
            List<Edge> also = new ArrayList<>(fed.subList(1, fed.size()));
            also.add(edge.covering(left));
            long got = refused == null ? make(fed.getFirst(), fed.getFirst().count(), also, bigger) : 0;
            if (got <= 0) {
                // Restore the original quantity with a fresh offer. Its old ids may still be predecessors of
                // other station turns, while a new hold now queues behind them; reviving those ids would loop.
                Why why = lastWhy;
                String short_ = madeShort;
                joinFailed.add(fed.getFirst().id());
                boolean was = restoring;
                restoring = true;
                try {
                    long original = fed.stream().mapToLong(Edge::count).sum();
                    Costs.Offer restore = bigger(near, made, original, making.offer().from());
                    if (restore == null || make(fed.getFirst(), fed.getFirst().count(), fed.subList(1, fed.size()), restore) <= 0) {
                        reopened.addAll(fed);
                    }
                } finally {
                    restoring = was;
                }
                lastWhy = why;
                madeShort = short_;
            }
            // What the old making's shared feeders also fed is fed again now.
            List<Edge> again = new ArrayList<>(reopened.subList(waiting, reopened.size()));
            reopened.subList(waiting, reopened.size()).clear();
            boolean was = restoring;
            restoring = true;
            try {
                for (Edge one : again) {
                    if (nodes.containsKey(one.consumer()) && fill(one) > 0) {
                        reopened.add(one);
                    }
                }
            } finally {
                restoring = was;
            }
            if (got > 0) {
                joinsWent.merge("joined", 1, Integer::sum);
                return left;
            }
            joinsWent.merge(refused != null ? refused : "laying afresh failed: " + madeShort, 1, Integer::sum);
        }
        return 0;
    }

    // The workshop's way to make all of `total` in one go, if it has one.
    private Costs.Offer bigger(WorkSite near, ItemSpec made, long total, Workshop from) {
        for (Costs.Offer offer : costs.offers(Produce.of(OWNER, near, made, total, sizing.batching(total)))) {
            if (offer.from().key().equals(from.key()) && yieldOf(offer.change().replacement(), made) >= total) {
                return offer;
            }
        }
        return null;
    }

    // What two makings cost apart: the work of both, since the same hands may do the one and then the other, and the
    // longer of their waits, since what they wait through goes on side by side.
    private static long apart(long one, long oneIdle, long other, long otherIdle) {
        if (one == Costs.UNREACHABLE || other == Costs.UNREACHABLE) {
            return Costs.UNREACHABLE;
        }
        return Costs.plus(Costs.plus(one - oneIdle, other - otherIdle), Math.max(oneIdle, otherIdle));
    }

    private static long waitFor(Costs.Offer offer) {
        return offer.change().holds().stream().mapToLong(Hold::estimatedWaitTicks).map(ticks -> Math.max(0, ticks)).max().orElse(0);
    }

    // The part of the work's reckoned time no hand works through: what it is reckoned at past its wind-up.
    private static long idle(Grown made) {
        long ticks = 0;
        for (Node node : made.nodes()) {
            if (node.spec().workload() instanceof Workload.Once once) {
                ticks += Math.max(0, node.spec().estimate() - once.baseTicks());
            }
        }
        return ticks;
    }

    // Whether nothing of a growth, nor of anything feeding it, is done or in hand: it may still be laid afresh.
    private boolean untouched(Expansion grown) {
        if (grown.started) {
            return false;
        }
        for (UUID id : grown.nodes) {
            if (holderOf(crew, id) != null || pinned.containsKey(id)) {
                return false;
            }
            for (Expansion fed : feeding.getOrDefault(id, List.of())) {
                if (!untouched(fed)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- the transfer ----

    // A chosen source becomes an intent; the refinement registry lays its material edges into this growth.
    private long transfer(Expansion grown, From from, To to, ItemSpec goods, long count) {
        Lent lent = new Lent(grown, from, to, goods, count);
        return refining.lay(lent.transfer.intent(), lent);
    }

    // What the plan lends the rule while it lays a transfer: the plan this round, and the one growth to lay into.
    private final class Lent implements Routing.Plan, Refinement.Context {

        private final Expansion grown;
        private final Transfer transfer;

        private Lent(Expansion grown, From from, To to, ItemSpec goods, long count) {
            this.grown = grown;
            this.transfer = Routing.request(from, to, goods, count, now.at(), this);
        }

        @Override
        public Transfer transfer() {
            return transfer;
        }

        @Override
        public Sizing sizing() {
            return sizing;
        }

        @Override
        public Optional<Refinement.Planning> planning() {
            return lent().planning();
        }

        @Override
        public Optional<Refinement.Laying> laying() {
            return Optional.of(this);
        }

        @Override
        public long attempt(LongSupplier work) {
            Mark mark = mark();
            try {
                long laid = work.getAsLong();
                if (laid > 0) {
                    return laid;
                }
            } catch (RuntimeException | LinkageError broken) {
                rollback(mark);
                throw broken;
            }
            rollback(mark);
            return 0;
        }

        @Override
        public Situation situation() {
            return now;
        }

        @Override
        public Crew crew() {
            return crew;
        }

        @Override
        public Set<Stand> stands(UUID node) {
            Set<Stand> at = standsOf(node);
            return at == null ? Set.of(new Stand(nodes.get(node).spec().site().where())) : at;
        }

        @Override
        public boolean eligible(Crew.Hand hand, UUID node) {
            return Fitness.of(hand, work(Weaver.this.together(node), peakLoad(Weaver.this.together(node)))).isEmpty();
        }

        @Override
        public boolean after(UUID node, Set<UUID> predecessors) {
            if (bonds.reaches(node, predecessors)) {
                return false;
            }
            predecessors.forEach(before -> grown.link(new Before(before, node)));
            return true;
        }

        @Override
        public boolean hold(Hold hold) {
            if (Expansion.take(List.of(hold)).isPresent()) {
                return false;
            }
            grown.holds.add(hold);
            return true;
        }

        @Override
        public boolean reserve(ResourceLocation resource, ResourceLocation unit, UUID owner,
                               long amount, long capacity) {
            Resource.Transport on = new Resource.Transport(resource, unit);
            if (amount < 0 || capacity < 0 || amount > capacity - claims.taken(on)) {
                return false;
            }
            book(on, owner, amount);
            return true;
        }

        @Override
        public Node node(UUID id) {
            return nodes.get(id);
        }

        @Override
        public Set<Stash> stores() {
            return now.stock().stashes();
        }

        @Override
        public boolean direct(Stash where) {
            return now.stock().direct(where);
        }

        @Override
        public int preference(Stash where, ItemSpec goods) {
            return now.stock().preference(where, goods);
        }

        @Override
        public Set<Stand> around(Stash where) {
            return now.stands().around(where, Operation.TAKE);
        }

        @Override
        public Set<Stand> putting(Stash where) {
            return now.stands().around(where, Operation.PUT);
        }

        @Override
        public Set<Stand> placing(UUID node, Stash where) {
            return Weaver.this.reaching(node, where, Operation.PUT);
        }

        @Override
        public boolean cutOff(Stash where, Set<Stand> around) {
            return Weaver.this.cutOff(where, around);
        }

        @Override
        public Set<Stand> reaching(UUID node, Stash where) {
            return Weaver.this.reaching(node, where);
        }

        @Override
        public Set<Stash> closure(UUID node) {
            return closureOf(node);
        }

        @Override
        public List<Stash> putsInto(UUID node) {
            return intoOf(node);
        }

        @Override
        public long roomFor(Stash where, ItemSpec concrete) {
            return now.stock().stores(where) ? Weaver.this.roomFor(where, concrete) : 0;
        }

        @Override
        public boolean feeds(Stash from, UUID consumer, ItemSpec concrete) {
            return Weaver.this.feeds(from, consumer, concrete);
        }

        @Override
        public long perTrip(ItemSpec concrete) {
            return sizing.perTrip(concrete);
        }

        @Override
        public Manifest lying(Stash where, ItemSpec concrete, long count) {
            return now.stock().manifest(where, concrete, claims.taken(new Resource.Lot(where, concrete))
                + claims.taken(new Resource.Transit(where, concrete)), count);
        }

        @Override
        public void add(Node node) {
            Weaver.this.add(grown, node);
        }

        @Override
        public void book(Resource on, UUID owner, long amount) {
            Weaver.this.book(grown, on, owner, amount);
        }

        @Override
        public void flow(Flow flow) {
            grown.flow(flow);
        }

        @Override
        public void together(UUID one, UUID other) {
            grown.together(Set.of(one, other));
        }

        @Override
        public void stand(UUID node, Set<Stand> stands) {
            touch(grown, Touch.standing(node, stands));
        }

        @Override
        public void carry(UUID node, Amount amount) {
            touch(grown, Touch.carrying(node, amount));
        }

        @Override
        public void from(UUID node, Stash where) {
            touch(grown, Touch.from(node, where));
        }

        @Override
        public void into(UUID node, Stash where) {
            touch(grown, Touch.into(node, where));
        }

        @Override
        public void hand(UUID node, Manifest goods) {
            touch(grown, Touch.handed(node, goods));
        }

        @Override
        public void by(UUID node, UUID worker) {
            touch(grown, Touch.by(node, worker));
        }

        @Override
        public Mark mark() {
            return grown.mark();
        }

        @Override
        public List<UUID> laidSince(Mark mark) {
            List<UUID> laid = List.copyOf(grown.nodes);
            return laid.subList(Math.min(((Expansion.Mark) mark).nodes(), laid.size()), laid.size());
        }

        @Override
        public void rollback(Mark mark) {
            Weaver.this.rollback(grown, (Expansion.Mark) mark);
        }

        @Override
        public boolean fits(UUID node) {
            return Weaver.this.fits(Set.of(node));
        }

        @Override
        public boolean fitsNow(UUID node) {
            Set<UUID> group = Weaver.this.together(node);
            return Fitness.anyoneNow(crew, work(group, peakLoad(group)));
        }

        @Override
        public void because(Why why) {
            lastWhy = why;
        }
    }

    // ---- cargo put away to tidy an idle pack or make room for waiting work ----

    private record RecoveryStore(Stash stash, Set<Stand> at, int ticks) {
    }

    // Unclaimed cargo goes into the nearest stores with room. Owning pending work must not prevent the very
    // unloading it waits on, but work already in hand keeps its pack until settlement.
    private void recover(boolean retry) {
        for (Crew.Hand hand : crew.hands()) {
            if (!stowing.contains(hand.id()) || !(retry || stowFresh.contains(hand.id()))) {
                continue;
            }
            if (hand.cargo().isEmpty() || standing(hand.id())) {
                stowFresh.remove(hand.id());
                stowing.remove(hand.id());
                continue;
            }
            if (hand.holding().isPresent()) {
                continue;
            }
            stowFresh.remove(hand.id());
            gapsOf.remove(hand.id());
            List<RecoveryStore> stores = new ArrayList<>();
            for (Stash stash : now.stock().stashes()) {
                if (!now.stock().stores(stash)) continue;
                Set<Stand> at = now.stands().around(stash, Operation.PUT);
                if (at.isEmpty()) {
                    continue;
                }
                Faring fare = now.ways().journey(hand.who(), hand.at(), cells(at), Urgency.BACKGROUND);
                if (!(fare instanceof Faring.No)) {
                    stores.add(new RecoveryStore(stash, at,
                        fare instanceof Faring.Yes yes ? yes.by().ticks() : walkAtLeast(hand.at(), stash.pos())));
                }
            }
            stores.sort(Comparator.comparingInt(RecoveryStore::ticks));
            Expansion grown = begin(new Expansion(hand.id()));
            List<Gap> short_ = new ArrayList<>();
            UUID before = null;
            Map<ItemSpec, Long> reserved = new LinkedHashMap<>();
            Why why = Why.NO_ROOM;
            for (ItemStack carried : hand.cargo()) {
                ItemSpec spec = Goods.specOf(carried);
                long used = reserved.computeIfAbsent(spec,
                    item -> claims.taken(new Resource.Cargo(hand.id(), item)));
                int skipped = (int) Math.min(used, carried.getCount());
                reserved.put(spec, used - skipped);
                int left = carried.getCount() - skipped;
                for (RecoveryStore store : puttingAway(stores, spec)) {
                    if (left == 0) {
                        break;
                    }
                    int count = (int) Math.min(left, roomFor(store.stash(), spec));
                    if (count <= 0) {
                        continue;
                    }
                    if (transfer(grown, new From.Carried(hand.id(),
                            List.of(carried.copyWithCount(count))), new To.Stored(store.stash()), spec, count) <= 0) {
                        why = lastWhy;
                        break;
                    }
                    UUID stow = grown.nodes.getLast();
                    if (before != null) {
                        grown.link(new Before(before, stow));
                    }
                    before = stow;
                    left -= count;
                }
                if (left > 0) {
                    short_.add(new Gap(hand.id(), hand.id(), spec, left, why));
                }
            }
            if (grown.nodes.isEmpty()) {
                remove(grown);
            }
            if (short_.isEmpty()) {
                stowing.remove(hand.id());
            } else {
                gapsOf.put(hand.id(), short_);
            }
        }
    }

    // Where goods are put away, nearest first: never into a store kept stocked with other goods while any other
    // store will take them, as that store is the colony's place for those goods, and whatever else goes in is lost
    // among them.
    private List<RecoveryStore> puttingAway(List<RecoveryStore> stores, ItemSpec spec) {
        List<RecoveryStore> ordered = new ArrayList<>(stores);
        ordered.sort(Comparator.comparingInt(
                (RecoveryStore store) -> -now.stock().preference(store.stash(), spec))
            .thenComparingInt(store -> now.stock().keepsOther(store.stash(), spec) ? 1 : 0)
            .thenComparingInt(RecoveryStore::ticks));
        return ordered;
    }

    // A store whose way there is still being worked out is as far as a straight walk to it at the least, so it is
    // weighed against the stores with a known way rather than put behind all of them, which sent goods past a store
    // at hand to the far one whose way happened to be known.
    private static int walkAtLeast(WorldPos from, WorldPos to) {
        if (!from.sameRealm(to)) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.min(Integer.MAX_VALUE - 1L,
            Math.round(Math.sqrt(from.cell().distSqr(to.cell())) / Gait.BLOCKS_PER_TICK));
    }

    // ---- the graph's bookkeeping ----

    private Expansion begin(Expansion grown) {
        live.add(grown);
        grown.bind(bonds);
        if (grown.isRequest()) {
            answering.put(grown.asked, grown);
        } else {
            feeding.computeIfAbsent(grown.serves.consumer(), key -> new ArrayList<>()).add(grown);
        }
        madeNow.add(grown);
        return grown;
    }

    // A further edge the growth feeds, from the same making.
    private void attach(Expansion grown, Edge edge) {
        grown.also.add(edge);
        feeding.computeIfAbsent(edge.consumer(), key -> new ArrayList<>()).add(grown);
    }

    // A consumer finished with its share of a shared making: the making goes on for the rest.
    private void detach(Expansion grown, UUID consumer) {
        grown.detached.add(consumer);
        List<Expansion> fed = feeding.get(consumer);
        if (fed != null && fed.remove(grown) && fed.isEmpty()) {
            feeding.remove(consumer);
        }
    }

    private void place(Expansion grown, Grown work) {
        for (Node node : work.nodes()) {
            add(grown, node);
        }
        work.links().forEach(grown::link);
        work.workerGroups().forEach(grown::together);
    }

    private void add(Expansion grown, Node node) {
        UUID id = node.id();
        nodes.put(id, node);
        grownBy.put(id, grown);
        grown.nodes.add(id);
        grownNow.add(id);
        dirty.add(id);
        if (choosing != null) {
            cooldowns.made(id, choosing);
        }
    }

    private void touch(Expansion grown, Touch touch) {
        dirty.add(touch.node());
        grown.touches.add(touch);
        touched.computeIfAbsent(touch.node(), key -> new ArrayList<>()).add(touch);
    }

    private void book(Expansion grown, Resource on, UUID owner, long amount) {
        if (amount <= 0) {
            return;
        }
        Claim claim = new Claim(on, owner, amount);
        claims.take(claim);
        grown.claims.add(claim);
    }

    // Takes a growth back: what was grown to feed its nodes first, then its nodes, bookings, touches and holds. A
    // making it shared with consumers of other growths goes too, and their edges are open again.
    private void undo(Expansion grown, Ending how) {
        if (!live.contains(grown)) {
            return;
        }
        for (UUID id : List.copyOf(grown.nodes)) {
            for (Expansion fed : List.copyOf(feeding.getOrDefault(id, List.of()))) {
                if (fed.shared()) {
                    for (Edge edge : fed.fed()) {
                        if (!grown.nodes.contains(edge.consumer()) && nodes.containsKey(edge.consumer())) {
                            reopened.add(edge);
                        }
                    }
                }
                undo(fed, Ending.REVOKED);
            }
        }
        for (UUID id : List.copyOf(grown.nodes)) {
            if (nodes.get(id) instanceof RunBoundary boundary) {
                closePool(boundary);
            }
            forget(id);
        }
        grown.nodes.clear();
        for (Claim claim : grown.claims) {
            claims.drop(claim);
            List<Claim> cargo = pinned.get(claim.owner());
            if (cargo != null && cargo.remove(claim) && cargo.isEmpty()) {
                pinned.remove(claim.owner());
            }
        }
        for (var loan : grown.loans) {
            loans.computeIfPresent(loan.draw.owner(), (id, draws) -> {
                draws.remove(loan);
                return draws.isEmpty() ? null : draws;
            });
            if (loan.returnable() && loan.pool.open) {
                loan.pool.adjust(claims, loan.input, loan.draw.amount());
            }
        }
        grown.settle(how);
        remove(grown);
    }

    private void remove(Expansion grown) {
        grown.unbind();
        for (Touch touch : grown.touches) {
            dirty.add(touch.node());
            List<Touch> on = touched.get(touch.node());
            if (on != null && on.remove(touch) && on.isEmpty()) {
                touched.remove(touch.node());
            }
        }
        live.remove(grown);
        if (grown.stowing != null) {
            grown.pool.hauling.remove(grown.stowing, grown);
        }
        if (grown.isRequest()) {
            answering.remove(grown.asked, grown);
        } else {
            for (UUID consumer : grown.consumers()) {
                List<Expansion> fed = feeding.get(consumer);
                if (fed != null && fed.remove(grown) && fed.isEmpty()) {
                    feeding.remove(consumer);
                }
            }
            if (grown.making != null) {
                List<Expansion> same = makings.get(grown.making.made());
                if (same != null && same.remove(grown) && same.isEmpty()) {
                    makings.remove(grown.making.made());
                }
            }
        }
        madeNow.remove(grown);
    }

    // Takes back what a growth gained since `mark`, as though it had stopped there.
    private void rollback(Expansion grown, Expansion.Mark mark) {
        List<Hold> holds = grown.holds.subList(mark.holds(), grown.holds.size());
        Expansion.release(List.copyOf(holds), Ending.DROPPED);
        holds.clear();
        List<UUID> added = List.copyOf(grown.nodes);
        for (UUID id : added.subList(mark.nodes(), added.size())) {
            forget(id);
            grown.nodes.remove(id);
        }
        List<Claim> booked = grown.claims.subList(mark.claims(), grown.claims.size());
        booked.forEach(claims::drop);
        booked.clear();
        List<Touch> said = grown.touches.subList(mark.touches(), grown.touches.size());
        for (Touch touch : said) {
            dirty.add(touch.node());
            List<Touch> on = touched.get(touch.node());
            if (on != null && on.remove(touch) && on.isEmpty()) {
                touched.remove(touch.node());
            }
        }
        said.clear();
        grown.truncate(mark);
    }

    private void forget(UUID node) {
        dirty.add(node);
        claims.release(node);
        cooldowns.forget(node);
        Node gone = nodes.remove(node);
        if (gone != null) {
            for (int at = 0; at < gone.spec().needs().size(); at++) {
                List<Gap> short_ = gapsOf.remove(Edge.idOf(node, at));
                if (short_ != null && weaving) {
                    endedGaps.addAll(short_);
                }
            }
        }
        grownBy.remove(node);
        grownNow.remove(node);
        pinned.remove(node);
        touched.remove(node);
    }

    // ---- reading the graph ----

    // The nodes one worker must do together with this one.
    private Set<UUID> together(UUID first) {
        return bonds.fellows(first, nodes::containsKey);
    }

    private UUID pinOf(UUID node) {
        for (Touch touch : touched.getOrDefault(node, List.of())) {
            if (touch.worker() != null) {
                return touch.worker();
            }
        }
        return null;
    }

    private List<UUID> cargoOwnersOf(UUID node) {
        return pinned.getOrDefault(node, List.of()).stream()
            .map(claim -> ((Resource.Cargo) claim.on()).resident()).toList();
    }

    // A group follows whoever is doing a step or whose pack supplies one.
    private Set<UUID> ownersOf(Set<UUID> group) {
        Set<UUID> owners = new LinkedHashSet<>();
        for (Crew.Hand hand : crew.hands()) {
            if (hand.holding().filter(group::contains).isPresent()) {
                owners.add(hand.id());
            }
        }
        for (UUID node : group) {
            owners.addAll(cargoOwnersOf(node));
            UUID worker = pinOf(node);
            if (worker != null) {
                owners.add(worker);
            }
        }
        return owners;
    }

    private static UUID holderOf(Crew crew, UUID node) {
        for (Crew.Hand hand : crew.hands()) {
            if (hand.holding().filter(node::equals).isPresent()) {
                return hand.id();
            }
        }
        return null;
    }

    private UUID workerOf(UUID node) {
        UUID holder = holderOf(crew, node);
        if (holder != null) {
            return holder;
        }
        List<UUID> carriers = cargoOwnersOf(node);
        return carriers.isEmpty() ? pinOf(node) : carriers.getFirst();
    }

    // Who must do the node: whoever has it in hand or carries goods for it, whoever a growth named, or whoever the
    // work names itself.
    private Optional<UUID> ownerOf(UUID node) {
        UUID worker = workerOf(node);
        return worker != null ? Optional.of(worker) : nodes.get(node).worker();
    }

    // Where a node may stand: where it says, narrowed by every growth that needs it to reach a store. Null when
    // it may stand anywhere and nothing has narrowed it yet.
    private Set<Stand> standsOf(UUID node) {
        Stances stances = nodes.get(node).spec().stances();
        Set<Stand> stands = stances instanceof Stances.Cells ? WorkStances.cellsOf(stances) : null;
        for (Touch touch : touched.getOrDefault(node, List.of())) {
            if (touch.stands().isEmpty()) {
                continue;
            }
            if (stands == null) {
                stands = new LinkedHashSet<>(touch.stands());
            } else {
                Set<Stand> both = new LinkedHashSet<>(stands);
                both.retainAll(touch.stands());
                stands = both.isEmpty() ? new LinkedHashSet<>(touch.stands()) : both;
            }
        }
        return stands;
    }

    private Set<Stand> reaching(UUID node, Stash where) {
        return reaching(node, where, Operation.TAKE);
    }

    private Set<Stand> reaching(UUID node, Stash where, Operation operation) {
        Set<Stand> stands = standsOf(node);
        if (stands == null) {
            return now.stands().around(where, operation);
        }
        Set<Stand> found = new LinkedHashSet<>();
        for (Stand cell : stands) {
            if (now.stands().around(where, operation).contains(cell)) {
                found.add(cell);
            }
        }
        return found;
    }

    private Set<Stash> closureOf(UUID node) {
        Set<Stand> stands = standsOf(node);
        return stands == null ? now.stock().stashes() : now.stands().reachedBy(stands);
    }

    private List<Stash> intoOf(UUID node) {
        List<Stash> into = new ArrayList<>();
        for (Touch touch : touched.getOrDefault(node, List.of())) {
            if (touch.into() != null && !into.contains(touch.into())) {
                into.add(touch.into());
            }
        }
        return into;
    }

    private Manifest cargoOf(UUID node) {
        List<Manifest> handed = new ArrayList<>();
        for (Touch touch : touched.getOrDefault(node, List.of())) {
            if (touch.cargo() != null) {
                handed.add(touch.cargo());
            }
        }
        return new Manifest(List.of(), List.of(), handed);
    }

    private Placement placement(UUID node) {
        Set<Stand> stands = standsOf(node);
        List<Amount> carrying = new ArrayList<>();
        Set<Stash> from = new LinkedHashSet<>();
        Set<Stash> into = new LinkedHashSet<>();
        for (Touch touch : touched.getOrDefault(node, List.of())) {
            if (touch.carrying() != null) {
                carrying.add(touch.carrying());
            }
            if (touch.from() != null) {
                from.add(touch.from());
            }
            if (touch.into() != null) {
                into.add(touch.into());
            }
        }
        // What the work leaves in the pack fills it as goods carried do, though nobody is promised it.
        Node worked = nodes.get(node);
        if (worked != null) {
            List<Amount> promised = new ArrayList<>(carrying.stream().filter(amount -> amount.count() > 0).toList());
            // Output booked directly into a store does not occupy the worker's pack.
            for (Claim claim : claims.since(node, 0)) {
                if (claim.on() instanceof Resource.Space space && into.contains(space.where())) {
                    promised.add(new Amount(space.spec(), claim.amount()));
                }
            }
            carrying.addAll(worked.planningLeaves(promised));
        }
        Expansion grown = grownBy.get(node);
        List<PoolClaims.Loan> booked = loans.get(node);
        var settlement = booked == null ? null : new Placement.DrawSettlement(node, new ArrayList<>(booked));
        return new Placement(stands == null ? Set.of() : stands, carrying, List.copyOf(from), List.copyOf(into),
            drawsOf(node), grown.pool == null ? null : grown.pool.budget, grown.stowing, settlement);
    }

    // What the node's own claims set aside for it to take: lots in stores, and goods in the pack, whether already
    // there or still to be put there by the step before it.
    private List<Placement.Draw> drawsOf(UUID node) {
        List<Placement.Draw> draws = new ArrayList<>();
        for (Claim claim : claims.since(node, 0)) {
            switch (claim.on()) {
                case Resource.Transit(Stash where, ItemSpec spec) ->
                    draws.add(new Placement.Draw(Optional.of(where), spec, claim.amount()));
                case Resource.Lot(Stash where, ItemSpec spec) ->
                    draws.add(new Placement.Draw(Optional.of(where), spec, claim.amount()));
                case Resource.Cargo(UUID resident, ItemSpec spec) ->
                    draws.add(new Placement.Draw(Optional.empty(), spec, claim.amount(), Optional.of(resident), Optional.empty()));
                case Resource.Handed(UUID by, ItemSpec spec) ->
                    draws.add(new Placement.Draw(Optional.empty(), spec, claim.amount(), Optional.empty(), Optional.of(by)));
                case Resource.Transport ignored -> {
                }
                case Resource.Space space -> {
                }
            }
        }
        return draws;
    }

    // ---- what the plan shows ----

    // The node as the schedule takes it: a carry whose goods are handed on to it shows those goods.
    private Vertex vertexOf(UUID id) {
        Node node = nodes.get(id);
        Node worked = node instanceof TransferNode transfer && transfer.handedOn()
            ? transfer.resolved(cargoOf(id)) : node;
        return new Vertex(id, worked, placement(id));
    }

    // Shows what changed since the plan was last shown: each piece of work touched since, with its old group's and
    // its new group's work, as a group is shown whole and bound to one worker; and each order said or unsaid since.
    private WeaveDelta publish() {
        Set<UUID> due = new LinkedHashSet<>(dirty);
        for (UUID id : dirty) {
            due.addAll(shownGroups.getOrDefault(id, Set.of()));
        }
        dirty.clear();
        Map<UUID, Vertex> put = new LinkedHashMap<>();
        Set<UUID> removed = new LinkedHashSet<>();
        Set<UUID> arrived = new LinkedHashSet<>();
        Map<UUID, Set<UUID>> groups = new LinkedHashMap<>();
        Map<UUID, Optional<UUID>> pins = new LinkedHashMap<>();
        Set<UUID> grouped = new HashSet<>();
        Set<UUID> fresh = new LinkedHashSet<>(grownNow);
        fresh.retainAll(nodes.keySet());
        for (UUID id : due) {
            if (!nodes.containsKey(id)) {
                if (shown.remove(id) != null) {
                    removed.add(id);
                    unshowOrdersOf(id);
                }
                shownGroups.remove(id);
                shownPins.remove(id);
                continue;
            }
            Vertex vertex = vertexOf(id);
            Vertex was = shown.put(id, vertex);
            if (was == null) {
                arrived.add(id);
            }
            if (!vertex.equals(was) || fresh.contains(id)) {
                put.put(id, vertex);
            }
            if (grouped.contains(id)) {
                continue;
            }
            // Groups are shown joined only through work still on the graph: work done binds nothing together.
            Set<UUID> group = together(id);
            grouped.addAll(group);
            Set<UUID> owners = ownersOf(group);
            for (UUID member : group) {
                nodes.get(member).worker().ifPresent(owners::add);
            }
            if (owners.size() > 1) {
                LOGGER.error("work that one resident must do together is bound to {}: {}", owners, group);
            }
            Set<UUID> together = group.size() > 1 ? Set.copyOf(group) : null;
            for (UUID member : group) {
                UUID pin = together == null ? ownerOf(member).orElse(null)
                    : owners.isEmpty() ? null : owners.iterator().next();
                if (!Objects.equals(together, shownGroups.get(member))) {
                    if (together == null) {
                        shownGroups.remove(member);
                    } else {
                        shownGroups.put(member, together);
                    }
                    groups.put(member, together == null ? Set.of() : together);
                }
                if (!Objects.equals(pin, shownPins.get(member))) {
                    if (pin == null) {
                        shownPins.remove(member);
                    } else {
                        shownPins.put(member, pin);
                    }
                    pins.put(member, Optional.ofNullable(pin));
                }
            }
        }
        for (UUID id : arrived) {
            bonds.forEachOrderOf(id, reordered::add);
        }
        Set<Before> linked = new LinkedHashSet<>();
        Set<Before> unlinked = new LinkedHashSet<>();
        for (Before order : reordered) {
            boolean should = bonds.ordered(order) && nodes.containsKey(order.from()) && nodes.containsKey(order.to());
            boolean is = shownAfter.getOrDefault(order.from(), Set.of()).contains(order.to());
            if (should && !is) {
                shownAfter.computeIfAbsent(order.from(), key -> new LinkedHashSet<>()).add(order.to());
                shownBefore.computeIfAbsent(order.to(), key -> new LinkedHashSet<>()).add(order.from());
                linked.add(order);
            } else if (!should && is) {
                unshow(order);
                unlinked.add(order);
            }
        }
        reordered.clear();
        List<Gone> gone = List.copyOf(going);
        going.clear();
        Set<UUID> held = new LinkedHashSet<>(awaiting.keySet());
        held.retainAll(asked.keySet());
        Optional<Set<UUID>> heldNow = held.equals(shownHeld) ? Optional.empty() : Optional.of(Set.copyOf(held));
        heldNow.ifPresent(now -> shownHeld = now);
        return new WeaveDelta(put, removed, linked, unlinked, groups, pins, fresh, gone, heldNow);
    }

    private void unshowOrdersOf(UUID id) {
        for (UUID to : List.copyOf(shownAfter.getOrDefault(id, Set.of()))) {
            unshow(new Before(id, to));
        }
        for (UUID from : List.copyOf(shownBefore.getOrDefault(id, Set.of()))) {
            unshow(new Before(from, id));
        }
    }

    private void unshow(Before order) {
        Set<UUID> out = shownAfter.get(order.from());
        if (out != null && out.remove(order.to()) && out.isEmpty()) {
            shownAfter.remove(order.from());
        }
        Set<UUID> in = shownBefore.get(order.to());
        if (in != null && in.remove(order.from()) && in.isEmpty()) {
            shownBefore.remove(order.to());
        }
    }

    boolean contains(UUID node) {
        return nodes.containsKey(node);
    }

    // How many pieces of work the plan holds now.
    public int size() {
        return nodes.size();
    }

    // ---- stock, room and reach ----

    // Only goods a store holds now and permits taking are there to draw. Goods on their way in belong to the growth that
    // brings them, which orders its own draws after them.
    private long drawable(Stash where, ItemSpec concrete) {
        if (!now.stock().takes(where)) {
            return 0;
        }
        return unclaimed(where, concrete, now.stock().heldAt(where, concrete));
    }

    private long unclaimed(Stash where, ItemSpec concrete, long stocked) {
        return Math.max(0, stocked - claims.taken(new Resource.Lot(where, concrete))
            - claims.taken(new Resource.Transit(where, concrete)));
    }

    private boolean drawableAnywhere(ItemSpec spec) {
        for (Stock.Lot lot : now.stock().lotsOf(spec)) {
            if (drawable(lot.where(), lot.concrete()) > 0) {
                return true;
            }
        }
        return false;
    }

    private long roomFor(Stash where, ItemSpec concrete) {
        return roomFor(where, concrete, false);
    }

    private long roomFor(Stash where, ItemSpec concrete, boolean directed) {
        if (!now.stock().holds(where)) {
            return 0;
        }
        Map<ItemSpec, Long> puts = new LinkedHashMap<>();
        Map<ItemSpec, Long> takes = new LinkedHashMap<>();
        claims.totals().forEach((resource, count) -> {
            if (resource instanceof Resource.Space space && space.where().equals(where)) {
                puts.merge(space.spec(), count, Long::sum);
            } else if (resource instanceof Resource.Lot lot && lot.where().equals(where)) {
                takes.merge(lot.spec(), count, Long::sum);
            }
        });
        return now.stock().roomAfter(where, concrete, directed, puts, takes);
    }

    // A reserve is never filled from another reserve of the same goods: the two would only empty into each other.
    private boolean feeds(Stash from, UUID consumer, ItemSpec concrete) {
        Node eats = nodes.get(consumer);
        if (!now.stock().takes(from) || !eats.acceptsInput(from)) {
            return false;
        }
        return !now.stock().reserves(from, concrete) || !(eats instanceof TransferNode put)
            || put.delivers().filter(into -> now.stock().reserves(into, concrete)).isEmpty();
    }

    private boolean cutOff(Stash where, Set<Stand> around) {
        return unreachable.computeIfAbsent(around,
            ignored -> now.ways().anyoneReaches(cells(around)) instanceof Faring.No);
    }

    private static Set<WorldPos> cells(Set<Stand> stands) {
        Set<WorldPos> found = new LinkedHashSet<>(stands.size());
        for (Stand stand : stands) {
            found.add(stand.cell());
        }
        return found;
    }

    // ---- who can do it ----

    // Whether one resident is fit to do all the work `members` must now be done together with, alone: every
    // binding the plan makes is checked here once it is on the graph, so the schedule is never handed work no one
    // can do. A growth that fails it gives back what it added.
    private boolean fits(Set<UUID> members) {
        Set<UUID> group = new LinkedHashSet<>();
        for (UUID id : members) {
            if (nodes.containsKey(id)) {
                group.addAll(together(id));
            }
        }
        return Fitness.anyone(crew, work(group, peakLoad(group)));
    }

    // The work of a group as fitness reads it: each step, everyone any of it is bound to, and the room it needs.
    private Fitness.Work work(Set<UUID> group, long cells) {
        Set<UUID> bound = ownersOf(group);
        List<NodeSpec> steps = new ArrayList<>(group.size());
        for (UUID id : group) {
            Node node = nodes.get(id);
            if (node != null) {
                node.worker().ifPresent(bound::add);
                steps.add(node.spec());
            }
        }
        return new Fitness.Work(steps, bound, cells);
    }

    // Whether someone is fit for every piece of `work` just placed, with whatever each must be done together with.
    private boolean fitsAll(Grown work) {
        for (Node node : work.nodes()) {
            if (!fits(Set.of(node.id()))) {
                return false;
            }
        }
        return true;
    }

    // ---- what packs hold ----

    // The most pack cells one worker doing the whole group needs at once. What a step picks up stays in the pack
    // only until a later step uses it, so the group is walked in an order its links allow - each time the step
    // that leaves the pack lightest - and the fullest the pack gets on the way is the load, not every pickup
    // added up: a chain that turns a log into planks into sticks into a tool never holds all of them together.
    private long peakLoad(Set<UUID> group) {
        Map<UUID, Set<UUID>> after = new HashMap<>();
        for (UUID id : group) {
            if (nodes.containsKey(id)) {
                after.put(id, new LinkedHashSet<>());
            }
        }
        for (UUID id : after.keySet()) {
            for (UUID earlier : bonds.before(id)) {
                if (after.containsKey(earlier)) {
                    after.get(id).add(earlier);
                }
            }
        }
        Map<ItemSpec, Long> held = new LinkedHashMap<>();
        Set<UUID> done = new LinkedHashSet<>();
        long peak = 0;
        while (done.size() < after.size()) {
            UUID next = null;
            long lightest = Long.MAX_VALUE;
            for (Map.Entry<UUID, Set<UUID>> one : after.entrySet()) {
                if (done.contains(one.getKey()) || !done.containsAll(one.getValue())) {
                    continue;
                }
                long cells = cells(carried(held, one.getKey()));
                if (cells < lightest) {
                    lightest = cells;
                    next = one.getKey();
                }
            }
            if (next == null) {
                // Links that loop: nothing can be ordered, so count every pickup as held at once.
                long all = 0;
                for (UUID id : after.keySet()) {
                    for (Touch touch : touched.getOrDefault(id, List.of())) {
                        if (touch.carrying() != null && touch.carrying().count() > 0) {
                            all += stacks(touch.carrying().spec(), touch.carrying().count());
                        }
                    }
                }
                return all;
            }
            held = carried(held, next);
            done.add(next);
            peak = Math.max(peak, lightest);
        }
        return peak;
    }

    private Map<ItemSpec, Long> carried(Map<ItemSpec, Long> held, UUID node) {
        Map<ItemSpec, Long> after = new LinkedHashMap<>(held);
        for (Touch touch : touched.getOrDefault(node, List.of())) {
            if (touch.carrying() != null) {
                after.merge(touch.carrying().spec(), touch.carrying().count(), Long::sum);
            }
        }
        return after;
    }

    private static long cells(Map<ItemSpec, Long> held) {
        long cells = 0;
        for (Map.Entry<ItemSpec, Long> one : held.entrySet()) {
            if (one.getValue() > 0) {
                cells += stacks(one.getKey(), one.getValue());
            }
        }
        return cells;
    }

    private static long stacks(ItemSpec goods, long count) {
        long size = Math.max(1, Goods.stackSize(goods));
        return (count + size - 1) / size;
    }

    // ---- what work makes ----

    // What bringing a workshop's inputs to it takes: each input walked from the nearest store or pack holding it to
    // the work that needs it, the dearest input setting it as the dearest sets an offer's cost. An input the offer
    // makes itself, or that nothing holds yet, walks nothing here; Costs prices its making.
    private long walkIn(Grown made) {
        long dearest = 0;
        for (Node node : made.nodes()) {
            WorkSite site = node.spec().site();
            for (Need need : node.spec().needs()) {
                ItemSpec input = need.spec();
                if (made.nodes().stream().anyMatch(maker -> maker.spec().gives().stream()
                        .anyMatch(gives -> admits(input, gives.spec())))) {
                    continue;
                }
                long nearest = walkIns.computeIfAbsent(new WalkIn(input, site.where()), ignored -> nearestHeld(input, site));
                if (nearest != Long.MAX_VALUE) {
                    dearest = Math.max(dearest, nearest);
                }
            }
        }
        return dearest;
    }

    private long nearestHeld(ItemSpec input, WorkSite site) {
        long nearest = Long.MAX_VALUE;
        for (Stock.Lot lot : now.stock().lotsOf(input)) {
            if (drawable(lot.where(), lot.concrete()) > 0) {
                nearest = Math.min(nearest, Transfer.walk(lot.where().pos(), site));
            }
        }
        for (Crew.Hand hand : crew.hands()) {
            if (hand.cargo().stream().anyMatch(stack -> Goods.matches(stack, input))) {
                nearest = Math.min(nearest, Transfer.walk(hand.at(), site));
            }
        }
        return nearest;
    }

    private static long walkFrom(Grown made, ItemSpec asked, WorkSite near) {
        long nearest = Long.MAX_VALUE;
        for (Node node : made.nodes()) {
            if (node.spec().gives().stream().anyMatch(gives -> admits(asked, gives.spec()))) {
                nearest = Math.min(nearest, Transfer.walk(node.spec().site().where(), near));
            }
        }
        return nearest == Long.MAX_VALUE ? 0L : nearest;
    }

    private static long yieldOf(Grown made, ItemSpec asked) {
        long total = 0;
        for (Node node : made.nodes()) {
            for (Amount gives : node.spec().gives()) {
                if (admits(asked, gives.spec())) {
                    total += gives.count();
                }
            }
        }
        return total;
    }

    private static ItemSpec concreteOf(Grown made, ItemSpec asked) {
        for (Node node : made.nodes()) {
            for (Amount gives : node.spec().gives()) {
                if (admits(asked, gives.spec())) {
                    return gives.spec();
                }
            }
        }
        return asked;
    }

    private static UUID makerOf(Grown made, ItemSpec concrete) {
        for (Node node : made.nodes()) {
            for (Amount gives : node.spec().gives()) {
                if (gives.spec().equals(concrete)) {
                    return node.id();
                }
            }
        }
        return null;
    }

    private static boolean admits(ItemSpec asked, ItemSpec given) {
        return asked.equals(given) || asked.admits(given) || Goods.overlap(asked, given);
    }
}
