package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.Gait;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Refinements;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Unmet;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.core.engine.plan.Expansion.Touch;
import io.github.izakyl.folkways.core.engine.plan.Transfer.From;
import io.github.izakyl.folkways.core.engine.plan.Transfer.To;
import io.github.izakyl.folkways.core.engine.plan.haul.Haul;
import io.github.izakyl.folkways.core.engine.plan.haul.Receipt;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Urgency;
import java.util.ArrayList;
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
import java.util.SequencedSet;
import java.util.Set;
import java.util.UUID;
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
 * ({@link Costs}). The way from a source to the node is a {@link Transfer}, laid by the rule goods move by
 * ({@link Carrying}) as material edges, and as the work that moves the goods when they need moving.
 *
 * <p>The graph has two kinds of edge: links, which only order work, and flows, which carry goods into it. A flow
 * whose source goes is fed again some other way; a link whose earlier end goes undone is left to the owner of its
 * later end ({@link io.github.izakyl.folkways.core.api.work.Unmet}).
 *
 * <p>Each growth is kept as one {@link Expansion}; where a node stands, what it carries and who must do it are read
 * off the expansions. Taking a growth back is removing its expansion, which opens its edge again. What a node makes
 * beyond what was asked stays with whoever made it: clearing it is that work's own business.
 */
public final class Weaver {

    public static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways", "supply");

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    private static final int MOST_FILLS = 50_000;

    private final Refining refining = new Refining(rules(), this::lent);
    private final Carrying carrying = Haul.CARRYING;
    private final Claims claims;
    private final Cooldowns cooldowns;

    // The graph: the work on it, the growth each node came from, and what each growth says about nodes.
    private final Map<UUID, Node> nodes = new LinkedHashMap<>();
    private final Map<UUID, Expansion> grownBy = new LinkedHashMap<>();
    private final Map<UUID, List<Expansion>> feeding = new LinkedHashMap<>();
    private final Set<Expansion> live = new LinkedHashSet<>();
    private final Map<UUID, List<Touch>> touched = new LinkedHashMap<>();

    // Who has work in hand: carrying goods for it, or merely at it.
    private final Map<UUID, UUID> pinned = new LinkedHashMap<>();
    private final Map<UUID, UUID> holding = new LinkedHashMap<>();

    // What was asked for, the growth answering each ask, and what is waiting to be grown.
    private final Map<UUID, Request> asked = new LinkedHashMap<>();
    private final Map<UUID, Expansion> answering = new LinkedHashMap<>();
    private final Set<UUID> sprouting = new LinkedHashSet<>();
    private final List<Edge> reopened = new ArrayList<>();
    private final Set<UUID> stowing = new LinkedHashSet<>();
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
    private final Set<Stash> looks = new LinkedHashSet<>();

    // This round.
    private final Set<UUID> grownNow = new LinkedHashSet<>();
    private final List<Expansion> madeNow = new ArrayList<>();
    private Situation now;
    private Crew crew;
    private Costs costs;
    private PackSizing sizing = new PackSizing(new Crew(List.of()));
    // What the crew could do when the plan was last checked against it, and whether it can do something else now.
    private List<Able> fitFor = List.of();
    private boolean refitting;
    // Residents the plan already has at work: in hand, or bound to work on the graph.
    private final Set<UUID> occupied = new LinkedHashSet<>();
    private final Map<Stash, Boolean> unreachable = new HashMap<>();

    private Choice choosing;
    private Why lastWhy = Why.NO_SOURCE;
    private boolean weaving;
    private int fills;

    public Weaver(Claims claims, Cooldowns cooldowns) {
        this.claims = claims;
        this.cooldowns = cooldowns;
    }

    // The core's own rules - deliveries - come before anything a plugin declares.
    private static List<Refinement> rules() {
        List<Refinement> rules = new ArrayList<>(List.of(Haul.DELIVERIES));
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

    // What a rule refining an intent this round is lent: the sizing of this round's crew.
    private Refinement.Context lent() {
        PackSizing now = sizing;
        return () -> now;
    }

    public void know(Situation now, Crew crew) {
        this.now = now;
        this.crew = crew;
        this.sizing = new PackSizing(crew);
        List<Able> able = crew.hands().stream().map(Able::of).toList();
        refitting |= !able.equals(fitFor);
        fitFor = able;
        unreachable.clear();
        this.costs = new Costs(now.workshops(), this::drawableAnywhere, claims::revision);
        occupied.clear();
        for (Crew.Hand hand : crew.hands()) {
            hand.holding().ifPresent(held -> occupied.add(hand.id()));
        }
        for (UUID id : nodes.keySet()) {
            ownerOf(id).ifPresent(occupied::add);
        }
    }

    public Weave grow(boolean retry) {
        if (weaving) {
            rollBack();
        }
        weaving = true;
        endedGaps.clear();
        noted.clear();
        refused.clear();
        grownNow.clear();
        madeNow.clear();
        fills = 0;
        choosing = null;

        if (refitting && !crew.hands().isEmpty()) {
            refitting = false;
            refit();
            reask();
        }
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
            due.sort(Comparator.comparingInt(id -> asked.containsKey(id) ? asked.get(id).work.rank() : 0));
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
        return projection();
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
            Request request = new Request(owner, new Grown(List.of(node), List.of(), work.rank(), List.of()));
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
        for (UUID id : together(node)) {
            (carrying ? pinned : holding).put(id, worker);
        }
    }

    public void released(UUID node) {
        if (nodes.containsKey(node)) {
            together(node).forEach(holding::remove);
        }
    }

    public void finished(UUID node) {
        unask(node);
        handOff(node);
        for (Expansion fed : List.copyOf(feeding.getOrDefault(node, List.of()))) {
            undo(fed, Ending.DONE);
        }
        Expansion grown = grownBy.get(node);
        forget(node);
        if (grown != null) {
            grown.nodes.remove(node);
            if (grown.nodes.isEmpty()) {
                settle(grown, Ending.DONE);
                if (grown.isRequest() || !nodes.containsKey(grown.serves.consumer())) {
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
        holding.values().removeIf(resident::equals);
        stowing.remove(resident);
        stowFresh.remove(resident);
        List<UUID> stranded = new ArrayList<>();
        for (UUID id : nodes.keySet()) {
            if (resident.equals(pinned.get(id)) || resident.equals(pinOf(id))) {
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

    public void closed() {
        for (Expansion grown : List.copyOf(live)) {
            undo(grown, Ending.REVOKED);
        }
        nodes.clear();
        grownBy.clear();
        feeding.clear();
        live.clear();
        touched.clear();
        pinned.clear();
        holding.clear();
        asked.clear();
        answering.clear();
        sprouting.clear();
        reopened.clear();
        stowing.clear();
        stowFresh.clear();
        awaiting.clear();
        going.clear();
        gapsOf.clear();
        endedGaps.clear();
        noted.clear();
        looks.clear();
        madeNow.clear();
        weaving = false;
        sizing = new PackSizing(new Crew(List.of()));
        fitFor = List.of();
        refitting = false;
    }

    // Goods a finished step leaves for the next one are that step's, not anyone else's: picked up into the pack the
    // next step is done from, or put into the store it draws from, until the stores are read again. What the step
    // put into a pack was claimed when it was planned, before anyone knew whose pack; now it is that resident's.
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
        UUID hand = workerOf(carrier);
        if (hand != null) {
            Set<ItemSpec> put = new LinkedHashSet<>();
            for (Touch touch : touched.getOrDefault(carrier, List.of())) {
                if (touch.carrying() != null && touch.carrying().count() > 0) {
                    put.add(touch.carrying().spec());
                }
            }
            for (ItemSpec spec : put) {
                for (Claim handed : claims.on(new Resource.Handed(carrier, spec))) {
                    claims.drop(handed);
                    claims.take(new Claim(new Resource.Cargo(hand, spec), handed.owner(), handed.amount()));
                }
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
            drop(grown.asked, how);
            return;
        }
        if (nodes.containsKey(grown.serves.consumer())) {
            reopened.add(grown.reopened());
        }
        undo(grown, how);
    }

    // ---- growing ----

    // What one resident could do, as far as fitness reads it: whom, which trades and tools, how big a pack.
    private record Able(UUID id, ResourceLocation kind, int pack, Set<ResourceLocation> trades,
                        List<ResourceLocation> tools) {

        static Able of(Crew.Hand hand) {
            Set<ResourceLocation> trades = new LinkedHashSet<>();
            hand.licences().forEach(licence -> trades.add(licence.vocation().id()));
            return new Able(hand.id(), hand.who().kind(), hand.packCapacity(), trades,
                hand.tools().stream().map(tool -> BuiltInRegistries.ITEM.getKey(tool.getItem())).toList());
        }
    }

    // The crew can do other things than it could: work no one is fit for any more goes back to what asked for it -
    // an edge to be fed again, a request to be grown again - so the schedule is never left work no one can be given.
    // Work a resident has in hand, or carries goods for, stays theirs.
    private void refit() {
        Set<UUID> seen = new HashSet<>();
        for (UUID id : List.copyOf(nodes.keySet())) {
            if (!nodes.containsKey(id) || seen.contains(id)) {
                continue;
            }
            Set<UUID> group = together(id);
            seen.addAll(group);
            if (group.stream().anyMatch(member -> holding.containsKey(member) || pinned.containsKey(member))
                    || Fitness.anyone(crew, work(group, peakLoad(group)))) {
                continue;
            }
            Set<Expansion> grown = new LinkedHashSet<>();
            for (UUID member : group) {
                Expansion one = grownBy.get(member);
                if (one != null) {
                    grown.add(one);
                }
            }
            List<Expansion> fed = grown.stream().filter(one -> !one.isRequest()).toList();
            if (!fed.isEmpty()) {
                for (Expansion one : fed) {
                    if (!one.nodes.isEmpty()) {
                        lose(one.nodes.getFirst(), Ending.DROPPED);
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
                if (noted.getLast().why() instanceof Why.Cooling) {
                    gapsOf.put(edge.id(), gaps);
                    resting.add(edge);
                    continue;
                }
                endedGaps.addAll(gaps);
                lose(edge.consumer(), Ending.DROPPED);
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
        Optional<RefusalKind> turned = take(result.holds());
        if (turned.isPresent()) {
            refused.add(new Refused(Optional.empty(), turned.get()));
            cooldowns.failed(choice);
            return;
        }
        int mark = noted.size();
        choosing = choice;
        Expansion grown = begin(new Expansion(id, work.rank()));
        grown.holds.addAll(result.holds());
        try {
            place(grown, work);
            waitOn(grown, work, after(result.holds()));
            askedWith(grown, request, work);
            if (!fitsAll(work)) {
                refused.add(new Refused(Optional.empty(), LaborRefusal.NO_ONE_FIT));
                cooldowns.failed(choice);
                undo(grown, Ending.DROPPED);
                return;
            }
            if (!(landed(grown) && open(grown, Set.of()))) {
                gapsOf.put(id, List.copyOf(noted.subList(mark, noted.size())));
                undo(grown, Ending.DROPPED);
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
        waitOn(grown, work, before);
        for (Set<UUID> group : request.together) {
            Set<UUID> bound = new LinkedHashSet<>(grown.nodes);
            for (UUID member : group) {
                Expansion other = answering.get(member);
                if (other != null && other != grown) {
                    bound.addAll(other.nodes);
                }
            }
            grown.together.add(bound);
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
            if (roomFor(into, goods.spec()) < goods.count()) {
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
            for (Edge edge : Edge.into(node, grown.rank, trail)) {
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
        if (++fills > MOST_FILLS) {
            if (fills == MOST_FILLS + 1) {
                LOGGER.error("the plan gave up after {} open edges with {} nodes on the graph - growth is not"
                    + " converging; this is a bug, not a shortage", MOST_FILLS, nodes.size());
            }
            return edge.count();
        }
        if (edge.loops() || !nodes.containsKey(edge.consumer())) {
            return edge.count();
        }
        long whole = edge.count();
        long left = whole - fromPack(edge, whole);
        Why why = Why.NO_SOURCE;
        boolean grew = left > 0;
        while (left > 0 && grew) {
            grew = false;
            for (Source source : sources(edge, left, whole)) {
                long got = switch (source) {
                    case Source.Lot(Stock.Lot lot) -> fromStore(edge, lot, left);
                    case Source.Offered(Costs.Offer offer) -> fromWorkshop(edge, offer, left);
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

    // Every way the edge could be fed, cheapest first: lots in stock cost the walk to the consumer, workshop offers
    // their work and inputs besides. Offers are asked for again with what is left each time round.
    private List<Source> sources(Edge edge, long left, long whole) {
        record Ranked(Source source, long cost, int kind, long plenty, String name) {
        }
        Node consumer = nodes.get(edge.consumer());
        WorkSite near = consumer.spec().site();
        List<Ranked> ranked = new ArrayList<>();
        for (Stock.Lot lot : now.stock().lotsOf(edge.spec())) {
            if (drawable(lot.where(), lot.concrete()) > 0 && feeds(lot.where(), edge.consumer(), lot.concrete())) {
                ranked.add(new Ranked(new Source.Lot(lot), Transfer.walk(lot.where().pos(), near), 0,
                    now.stock().available(lot.concrete()), lot.where().toString()));
            }
        }
        if (costs.reachable(edge.spec(), near, edge.trail())) {
            for (Costs.Offer offer : costs.offers(Produce.of(OWNER, near, edge.spec(), left,
                    sizing.batching(whole)))) {
                Grown made = offer.change().replacement();
                if (yieldOf(made, edge.spec()) <= 0) {
                    continue;
                }
                long cost = costs.of(made, near, edge.upstream());
                if (cost != Costs.UNREACHABLE) {
                    ranked.add(new Ranked(new Source.Offered(offer),
                        Costs.plus(cost, walkFrom(made, edge.spec(), near)), 1, 0, ""));
                }
            }
        }
        ranked.sort(Comparator.comparingLong(Ranked::cost).thenComparingInt(Ranked::kind)
            .thenComparing(Comparator.comparingLong(Ranked::plenty).reversed()).thenComparing(Ranked::name));
        return ranked.stream().map(Ranked::source).toList();
    }

    // Goods already in a worker's pack: whoever carries them does the consumer's work, and nothing is moved.
    private long fromPack(Edge edge, long count) {
        Node consumer = nodes.get(edge.consumer());
        if (consumer instanceof Receipt) {
            return 0;
        }
        // Whoever the consumer's work is already bound to - through any step it must be done together with -
        // is the only one whose pack can feed it: goods from another's pack would split the work between two.
        Set<UUID> bound = pinsOf(together(consumer.id()));
        consumer.worker().ifPresent(bound::add);
        if (bound.size() > 1) {
            return 0;
        }
        // The goods are in the pack already: what it takes is fitness for all of the consumer's group.
        Fitness.Work work = work(together(edge.consumer()), 0);
        for (Crew.Hand hand : crew.hands()) {
            if (occupied.contains(hand.id()) || Fitness.of(hand, work).isPresent()) {
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
            grown.covers = transfer(grown, new Transfer(new From.Carried(hand.id(), taken), new To.Feeding(edge),
                edge.spec(), served));
            return served;
        }
        return 0;
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
                moved = transfer(grown, new Transfer(new From.Stocked(lot.where()), new To.Feeding(edge),
                    lot.concrete(), can - got));
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

    // A workshop's offer, taken: its work, the carry of what it makes to the consumer, and the work's own needs
    // fed in turn - all of it, or none.
    private long fromWorkshop(Edge edge, Costs.Offer offer, long left) {
        Refinement.Change change = offer.change();
        ItemSpec made = concreteOf(change.replacement(), edge.spec());
        Choice choice = new Choice(edge.id(), made, offer.from().key());
        if (cooldowns.shut(choice)) {
            lastWhy = new Why.Cooling(choice);
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
        Optional<RefusalKind> turned = take(holds);
        Set<UUID> after = after(holds);
        if (turned.isEmpty() && reaches(edge.consumer(), after)) {
            release(holds, Ending.DROPPED);
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
            waitOn(grown, work, after);
            if (!fitsAll(work)) {
                undo(grown, Ending.DROPPED);
                lastWhy = Why.NO_HAND;
                return 0;
            }
            long yield = yieldOf(work, made);
            long covers = Math.min(left, yield);
            UUID maker = makerOf(work, made);
            if (maker == null
                    || transfer(grown, new Transfer(new From.Made(maker, yield), new To.Feeding(edge), made,
                        covers)) < covers
                    || !open(grown, edge.upstream())) {
                undo(grown, Ending.DROPPED);
                return 0;
            }
            grown.covers = covers;
            return covers;
        } catch (RuntimeException | LinkageError broken) {
            undo(grown, Ending.DROPPED);
            throw broken;
        }
    }

    // ---- the transfer ----

    // The one way goods move on the plan: a transfer, laid by the rule goods move by into the growth it is for.
    private long transfer(Expansion grown, Transfer transfer) {
        return carrying.lay(transfer, new Lent(grown));
    }

    // What the plan lends the rule while it lays a transfer: the plan this round, and the one growth to lay into.
    private final class Lent implements Laying {

        private final Expansion grown;

        private Lent(Expansion grown) {
            this.grown = grown;
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
        public Set<Stand> around(Stash where) {
            return now.stands().around(where);
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
            return Weaver.this.roomFor(where, concrete);
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
            return now.stock().manifest(where, concrete, claims.taken(new Resource.Lot(where, concrete)), count);
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
            grown.flows.add(flow);
        }

        @Override
        public void together(UUID one, UUID other) {
            grown.together.add(Set.of(one, other));
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

    // ---- the cargo a pack holds when nothing is asked of it ----

    private record RecoveryStore(Stash stash, Set<Stand> at, int ticks) {
    }

    // An idle worker's unclaimed cargo goes into the nearest stores with room.
    private void recover(boolean retry) {
        for (Crew.Hand hand : crew.hands()) {
            if (!stowing.contains(hand.id()) || !(retry || stowFresh.contains(hand.id()))) {
                continue;
            }
            stowFresh.remove(hand.id());
            if (hand.cargo().isEmpty() || standing(hand.id())) {
                stowing.remove(hand.id());
                continue;
            }
            if (occupied.contains(hand.id())) {
                continue;
            }
            gapsOf.remove(hand.id());
            List<RecoveryStore> stores = new ArrayList<>();
            for (Stash stash : now.stock().stashes()) {
                Set<Stand> at = now.stands().around(stash);
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
            Expansion grown = begin(new Expansion(hand.id(), 0));
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
                    if (transfer(grown, new Transfer(new From.Carried(hand.id(),
                            List.of(carried.copyWithCount(count))), new To.Stored(store.stash()), spec, count)) <= 0) {
                        why = lastWhy;
                        break;
                    }
                    UUID stow = grown.nodes.getLast();
                    if (before != null) {
                        grown.links.add(new Before(before, stow));
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
                (RecoveryStore store) -> now.stock().keepsOther(store.stash(), spec) ? 1 : 0)
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
        if (grown.isRequest()) {
            answering.put(grown.asked, grown);
        } else {
            feeding.computeIfAbsent(grown.serves.consumer(), key -> new ArrayList<>()).add(grown);
        }
        madeNow.add(grown);
        return grown;
    }

    private void place(Expansion grown, Grown work) {
        for (Node node : work.nodes()) {
            add(grown, node);
        }
        grown.links.addAll(work.links());
        grown.together.addAll(work.workerGroups());
    }

    private void add(Expansion grown, Node node) {
        UUID id = node.id();
        nodes.put(id, node);
        grownBy.put(id, grown);
        grown.nodes.add(id);
        grownNow.add(id);
        if (choosing != null) {
            cooldowns.made(id, choosing);
        }
    }

    private void touch(Expansion grown, Touch touch) {
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

    // Takes a growth back: what was grown to feed its nodes first, then its nodes, bookings, touches and holds.
    private void undo(Expansion grown, Ending how) {
        if (!live.contains(grown)) {
            return;
        }
        for (UUID id : List.copyOf(grown.nodes)) {
            for (Expansion fed : List.copyOf(feeding.getOrDefault(id, List.of()))) {
                undo(fed, Ending.REVOKED);
            }
        }
        for (UUID id : List.copyOf(grown.nodes)) {
            forget(id);
        }
        grown.nodes.clear();
        grown.claims.forEach(claims::drop);
        settle(grown, how);
        remove(grown);
    }

    private void settle(Expansion grown, Ending how) {
        if (!grown.settled) {
            grown.settled = true;
            release(grown.holds, how);
        }
    }

    private void remove(Expansion grown) {
        for (Touch touch : grown.touches) {
            List<Touch> on = touched.get(touch.node());
            if (on != null && on.remove(touch) && on.isEmpty()) {
                touched.remove(touch.node());
            }
        }
        live.remove(grown);
        if (grown.isRequest()) {
            answering.remove(grown.asked, grown);
        } else {
            List<Expansion> fed = feeding.get(grown.serves.consumer());
            if (fed != null && fed.remove(grown) && fed.isEmpty()) {
                feeding.remove(grown.serves.consumer());
            }
        }
        madeNow.remove(grown);
    }

    // Takes back what a growth gained since `mark`, as though it had stopped there.
    private void rollback(Expansion grown, Expansion.Mark mark) {
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
            List<Touch> on = touched.get(touch.node());
            if (on != null && on.remove(touch) && on.isEmpty()) {
                touched.remove(touch.node());
            }
        }
        said.clear();
        grown.links.subList(mark.links(), grown.links.size()).clear();
        grown.flows.subList(mark.flows(), grown.flows.size()).clear();
        grown.together.subList(mark.together(), grown.together.size()).clear();
    }

    private void forget(UUID node) {
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
        holding.remove(node);
        touched.remove(node);
    }

    private static Optional<RefusalKind> take(List<Hold> holds) {
        List<Hold> taken = new ArrayList<>(holds.size());
        for (Hold hold : holds) {
            Optional<RefusalKind> turned;
            try {
                turned = hold.take();
            } catch (RuntimeException | LinkageError broken) {
                LOGGER.error("a hold threw being taken", broken);
                turned = Optional.of(LaborRefusal.WORK_BROKE);
            }
            if (turned.isPresent()) {
                release(taken, Ending.DROPPED);
                return turned;
            }
            taken.add(hold);
        }
        return Optional.empty();
    }

    // The work on the graph that what the holds hold is spoken for by until it is done.
    private Set<UUID> after(List<Hold> holds) {
        Set<UUID> after = new LinkedHashSet<>();
        for (Hold hold : holds) {
            after.addAll(hold.after());
        }
        after.retainAll(nodes.keySet());
        return after;
    }

    // Work taken in its turn starts after the work its holds wait for: each of its first steps follows them.
    private static void waitOn(Expansion grown, Grown work, Set<UUID> after) {
        if (after.isEmpty()) {
            return;
        }
        Set<UUID> first = new LinkedHashSet<>();
        work.nodes().forEach(node -> first.add(node.id()));
        work.links().forEach(link -> first.remove(link.to()));
        for (UUID before : after) {
            for (UUID id : first) {
                grown.links.add(new Before(before, id));
            }
        }
    }

    // Whether any of `targets` comes after `from` on the graph: work that waits on them and feeds `from` would wait
    // on itself.
    private boolean reaches(UUID from, Set<UUID> targets) {
        if (targets.isEmpty()) {
            return false;
        }
        Map<UUID, List<UUID>> next = new HashMap<>();
        for (Expansion grown : expansions()) {
            for (Before order : orders(grown)) {
                next.computeIfAbsent(order.from(), key -> new ArrayList<>()).add(order.to());
            }
        }
        Set<UUID> seen = new LinkedHashSet<>();
        List<UUID> pending = new ArrayList<>(List.of(from));
        while (!pending.isEmpty()) {
            UUID id = pending.removeLast();
            if (targets.contains(id)) {
                return true;
            }
            if (seen.add(id)) {
                pending.addAll(next.getOrDefault(id, List.of()));
            }
        }
        return false;
    }

    private static void release(List<Hold> holds, Ending how) {
        for (Hold hold : holds) {
            try {
                hold.release(how);
            } catch (RuntimeException | LinkageError broken) {
                LOGGER.error("a hold threw being released", broken);
            }
        }
    }

    // ---- reading the graph ----

    // What a growth says must come before what: its links, and its flows from work to work.
    private static List<Before> orders(Expansion grown) {
        List<Before> orders = new ArrayList<>(grown.links);
        for (Flow flow : grown.flows) {
            flow.after().ifPresent(from -> orders.add(new Before(from, flow.to())));
        }
        return orders;
    }

    private List<Expansion> expansions() {
        return List.copyOf(live);
    }

    // The nodes one worker must do together with this one.
    private Set<UUID> together(UUID first) {
        Set<UUID> group = new LinkedHashSet<>(List.of(first));
        List<Set<UUID>> sets = new ArrayList<>();
        for (Expansion grown : expansions()) {
            sets.addAll(grown.together);
        }
        boolean grew;
        do {
            grew = false;
            for (Set<UUID> set : sets) {
                if (!Collections.disjoint(set, group)) {
                    grew |= group.addAll(set);
                }
            }
        } while (grew);
        group.removeIf(id -> !id.equals(first) && !nodes.containsKey(id));
        return group;
    }

    private UUID pinOf(UUID node) {
        for (Touch touch : touched.getOrDefault(node, List.of())) {
            if (touch.worker() != null) {
                return touch.worker();
            }
        }
        return null;
    }

    // Everyone some node of the group is bound to.
    private Set<UUID> pinsOf(Set<UUID> group) {
        Set<UUID> pins = new LinkedHashSet<>();
        for (UUID id : group) {
            if (nodes.containsKey(id)) {
                UUID pin = workerOf(id);
                if (pin != null) {
                    pins.add(pin);
                }
            }
        }
        return pins;
    }

    private UUID workerOf(UUID node) {
        UUID holder = holding.get(node);
        if (holder != null) {
            return holder;
        }
        UUID carrier = pinned.get(node);
        return carrier != null ? carrier : pinOf(node);
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
        Set<Stand> stands = stances instanceof Stances.Wherever ? null : Stands.cellsOf(stances);
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
        Set<Stand> stands = standsOf(node);
        if (stands == null) {
            return now.stands().around(where);
        }
        Set<Stand> found = new LinkedHashSet<>();
        for (Stand cell : stands) {
            if (now.stands().reach(cell).contains(where)) {
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
        return new Placement(stands == null ? Set.of() : stands, carrying, List.copyOf(from), List.copyOf(into),
            drawsOf(node));
    }

    // What the node's own claims set aside for it to take: lots in stores, and goods in the pack, whether already
    // there or still to be put there by the step before it.
    private List<Placement.Draw> drawsOf(UUID node) {
        List<Placement.Draw> draws = new ArrayList<>();
        for (Claim claim : claims.since(node, 0)) {
            switch (claim.on()) {
                case Resource.Lot(Stash where, ItemSpec spec) ->
                    draws.add(new Placement.Draw(Optional.of(where), spec, claim.amount()));
                case Resource.Cargo(UUID resident, ItemSpec spec) ->
                    draws.add(new Placement.Draw(Optional.empty(), spec, claim.amount()));
                case Resource.Handed(UUID by, ItemSpec spec) ->
                    draws.add(new Placement.Draw(Optional.empty(), spec, claim.amount()));
                case Resource.Space space -> {
                }
            }
        }
        return draws;
    }

    private Weave projection() {
        Map<UUID, Vertex> vertices = new LinkedHashMap<>();
        nodes.forEach((id, node) -> {
            Node worked = node instanceof TransferNode transfer && transfer.handedOn()
                ? transfer.resolved(cargoOf(id)) : node;
            vertices.put(id, new Vertex(id, worked, placement(id)));
        });
        Set<Before> links = new LinkedHashSet<>();
        List<Flow> flows = new ArrayList<>();
        Map<UUID, Set<UUID>> groups = new LinkedHashMap<>();
        for (Expansion grown : expansions()) {
            for (Before link : grown.links) {
                if (vertices.containsKey(link.from()) && vertices.containsKey(link.to())) {
                    links.add(link);
                }
            }
            for (Flow flow : grown.flows) {
                if (vertices.containsKey(flow.to()) && flow.after().map(vertices::containsKey).orElse(true)) {
                    flows.add(flow);
                }
            }
            for (Set<UUID> set : grown.together) {
                Set<UUID> group = new LinkedHashSet<>();
                for (UUID id : set) {
                    if (nodes.containsKey(id)) {
                        group.addAll(groups.getOrDefault(id, Set.of(id)));
                    }
                }
                for (UUID id : group) {
                    groups.put(id, group);
                }
            }
        }
        // A group is done by one resident: whoever has some of it in hand, or else whoever any of it is bound to.
        // Two of them in one group is a plan the schedule cannot keep, and a bug in how it was grown.
        List<Set<UUID>> together = List.copyOf(new LinkedHashSet<>(groups.values()));
        Map<UUID, UUID> pins = new LinkedHashMap<>();
        for (Set<UUID> group : together) {
            SequencedSet<UUID> owners = new LinkedHashSet<>();
            group.stream().map(holding::get).filter(Objects::nonNull).forEach(owners::add);
            group.forEach(id -> ownerOf(id).ifPresent(owners::add));
            if (owners.size() > 1) {
                LOGGER.error("work that one resident must do together is bound to {}: {}", owners, group);
            }
            if (!owners.isEmpty()) {
                UUID owner = owners.getFirst();
                group.forEach(id -> pins.put(id, owner));
            }
        }
        for (UUID id : nodes.keySet()) {
            if (!groups.containsKey(id)) {
                ownerOf(id).ifPresent(owner -> pins.put(id, owner));
            }
        }
        pins.keySet().retainAll(vertices.keySet());
        Set<UUID> fresh = new LinkedHashSet<>(grownNow);
        fresh.retainAll(vertices.keySet());
        Map<UUID, Grown> pending = new LinkedHashMap<>();
        asked.forEach((id, request) -> {
            if (request.pending != null) {
                pending.put(id, request.pending.graph());
            }
        });
        Map<UUID, Integer> ranks = new LinkedHashMap<>();
        for (UUID id : vertices.keySet()) {
            int rank = grownBy.get(id).rank;
            if (rank != 0) {
                ranks.put(id, rank);
            }
        }
        List<Gone> gone = List.copyOf(going);
        going.clear();
        Set<UUID> held = new LinkedHashSet<>(awaiting.keySet());
        held.retainAll(asked.keySet());
        return new Weave(vertices, List.copyOf(links), together, pins, fresh, pending, ranks, gone, flows, held);
    }

    // ---- stock, room and reach ----

    // Only goods a store holds now are there for anyone to draw. Goods on their way in belong to the growth that
    // brings them, which orders its own draws after them.
    private long drawable(Stash where, ItemSpec concrete) {
        return Math.max(0, now.stock().heldAt(where, concrete) - claims.taken(new Resource.Lot(where, concrete)));
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
        if (!now.stock().holds(where)) {
            return 0;
        }
        return Math.max(0, now.stock().roomAt(where, concrete)
            + claims.taken(new Resource.Lot(where, concrete))
            - claims.taken(new Resource.Space(where, concrete)));
    }

    // A reserve is never filled from another reserve of the same goods: the two would only empty into each other.
    private boolean feeds(Stash from, UUID consumer, ItemSpec concrete) {
        Node eats = nodes.get(consumer);
        if (!eats.acceptsInput(from)) {
            return false;
        }
        return !now.stock().reserves(from, concrete) || !(eats instanceof TransferNode put)
            || put.delivers().filter(into -> now.stock().reserves(into, concrete)).isEmpty();
    }

    private boolean cutOff(Stash where, Set<Stand> around) {
        return unreachable.computeIfAbsent(where,
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
        Set<UUID> bound = pinsOf(group);
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
        for (Expansion grown : expansions()) {
            for (Before order : orders(grown)) {
                if (after.containsKey(order.from()) && after.containsKey(order.to())) {
                    after.get(order.to()).add(order.from());
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
