package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.resident.body.Keenness;
import io.github.izakyl.folkways.core.api.terms.Gait;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Journey;
import io.github.izakyl.folkways.core.engine.travel.Urgency;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

final class Tours {

    // How many rearrangements one schedule may try before it settles for what it has; counted, not timed, so
    // the same world always plans the same way.
    private static final int MOST_TRIES = 4_096;
    private static final int LONGEST_RUN = 3;
    // A move must look at least this many ticks shorter by straight lines before it is tried for real.
    private static final double SAVING = 0.5;
    private static final int MOST_DECLINED = 4_096;
    private static final int MAX_FARES = 32_768;
    private static final int TIER_TICKS = 400;
    private static final int MOST_PENDING_FARES = 12;
    private static final int RIDE_SPEEDUP = 4;
    // How many places earlier on routes one node may be laid out at, when it fits on the end of none.
    private static final int MOST_INSERTS = 16;
    // How many routes one piece of work may be tried on beside work already laid at its place, and how much of the
    // work still to come with it is counted when it is.
    private static final int MOST_JOINS = 4;
    private static final int MOST_ONWARD = 4;
    // How much of a route may come after work put in beside other work: all of it is laid out again.
    private static final int MOST_TAIL = 16;
    // How many pieces of work one schedule may hand off the longest route, and how many pieces from its end each
    // hand-off looks at before giving up.
    private static final int MOST_SHEDS = 64;
    private static final int SHED_LOOKS = 8;
    // How much of the work left past the horizon one schedule tries again.
    private static final int MOST_BEYOND_TRIES = 256;
    // What one step of a chain of work is taken to cost in walking, when chains are ranked by how much hangs on them.
    private static final int HOP_TICKS = 100;
    private static final long BEGUN = Long.MAX_VALUE / 2;

    // How far past now work is handed out; see FolkwaysConfig.planHorizonTicks.
    private final int horizon;
    // How deep a route is before the horizon cuts what joins it; see FolkwaysConfig.planHorizonDepth.
    private final int depth;

    private final Map<UUID, Seat> seats = new LinkedHashMap<>();
    private final Map<Stand, List<Span>> spots = new LinkedHashMap<>();
    private final Map<UUID, Placed> settled = new LinkedHashMap<>();
    private final Map<UUID, UUID> seatOf = new LinkedHashMap<>();
    private final Map<UUID, Unassigned> waiting = new LinkedHashMap<>();
    private final Map<UUID, Placement> seen = new LinkedHashMap<>();
    private long origin = -1L;

    // The plan as this schedule keeps it, and the work grown afresh since the schedule last ran.
    private WeaveView weave = new WeaveView();
    private Set<UUID> fresh = Set.of();
    private Ways ways;
    private int now;
    private final Map<UUID, UUID> heldBy = new HashMap<>();
    // Work left past the horizon, and the time it would come within it if the routes before it kept their pace: it
    // is tried again then, or sooner when routes are cut short, a few at a time.
    private final Map<UUID, Integer> beyondUntil = new HashMap<>();
    // Seats whose routes changed this schedule: only their order is worth trying to improve.
    private final Set<Seat> changed = new HashSet<>();
    private final Map<Route, Faring> fares = new LinkedHashMap<>();
    // The ways this schedule has asked to be found: weighing hands asks for none, so each is asked for once.
    private final Set<Route> sought = new HashSet<>();
    private int tries;
    // Orders that looked shorter by straight lines but were not once laid out for real, so later schedules do
    // not ask for the same ways again.
    private final Set<List<UUID>> declined = new HashSet<>();
    // What this schedule is laying: whether routes were cut or the crew changed, whether everything is tried again,
    // the work it has laid, how much work past the horizon it has tried, and who it found fit for which work.
    private boolean moved;
    private boolean retry;
    private final Set<UUID> laidNow = new HashSet<>();
    private int beyondTried;
    private final Map<UUID, byte[]> fits = new HashMap<>();
    private final Set<UUID> overloaded = new LinkedHashSet<>();

    // What a route carries is counted per kind of goods, so many small loads of one thing share their stacks.
    private record Placed(int start, int finish, Stand at, int base, Map<ItemSpec, Long> held) {

        Placed {
            held = Map.copyOf(held);
        }
    }

    private record Route(ResourceLocation kind, WorldPos from, List<WorldPos> goals) {
    }

    private static final class Seat {

        private Crew.Hand hand;
        private final List<UUID> route = new ArrayList<>();
        private final List<Placed> when = new ArrayList<>();
        private WorldPos cursor;
        private int clock;
        private int base;
        private Map<ItemSpec, Long> held = Map.of();
        // Where on the route the first work with no end is, past the route's end when there is none.
        private int endless = Integer.MAX_VALUE;
        // Where this seat stands in the crew this schedule.
        private int slot;
        // The cells the route's work is done from, and how much of it from each.
        private final Map<WorldPos, Integer> stood = new HashMap<>();

        Seat(Crew.Hand hand) {
            this.hand = hand;
        }

        void resume(int now) {
            Placed last = when.isEmpty() ? null : when.getLast();
            cursor = last == null ? hand.at() : last.at().cell();
            clock = Math.max(now, last == null ? 0 : last.finish());
            base = last == null ? hand.packCapacity() - hand.packCells() : last.base();
            held = last == null ? Map.of() : last.held();
        }

        Map<ItemSpec, Long> with(List<Amount> carrying) {
            if (carrying.isEmpty()) {
                return held;
            }
            Map<ItemSpec, Long> after = new LinkedHashMap<>(held);
            for (Amount amount : carrying) {
                after.merge(amount.spec(), amount.count(), Long::sum);
            }
            after.values().removeIf(count -> count == 0);
            return after;
        }
    }

    Tours() {
        this(FolkwaysConfig.planHorizonTicks(), FolkwaysConfig.planHorizonDepth());
    }

    Tours(int horizon) {
        this(horizon, 0);
    }

    Tours(int horizon, int depth) {
        this.horizon = horizon;
        this.depth = depth;
    }

    int horizon() {
        return horizon;
    }

    // A schedule for the plan as `weave` now holds it, `fresh` being the work grown afresh since the last one.
    Schedule schedule(WeaveView weave, Set<UUID> fresh, Crew crew, Ways ways, long tick, boolean retry) {
        if (origin < 0L) {
            origin = tick;
        }
        this.now = (int) Math.clamp(tick - origin, 0L, Integer.MAX_VALUE);
        this.weave = weave;
        this.fresh = fresh;
        this.ways = ways;
        fares.clear();
        sought.clear();
        changed.clear();
        fits.clear();
        laidNow.clear();
        beyondTried = 0;
        this.retry = retry;
        index(crew);

        moved = seat(crew);
        moved |= cut();
        List<Seat> line = new ArrayList<>(crew.hands().size());
        for (Crew.Hand hand : crew.hands()) {
            line.add(seats.get(hand.id()));
        }
        waiting.keySet().retainAll(weave.vertices().keySet());
        beyondUntil.keySet().retainAll(waiting.keySet());

        List<UUID> order = topological();
        Set<UUID> tried = waves(order, line);
        for (UUID id : order) {
            if (!tried.contains(id)) {
                attempt(id, line);
            }
        }
        seen.keySet().removeIf(id -> !settled.containsKey(id) && !waiting.containsKey(id));
        if (!laidNow.isEmpty()) {
            improve(line);
        }
        fits.clear();

        Map<UUID, Tour> tours = new LinkedHashMap<>();
        for (Seat seat : line) {
            if (!seat.route.isEmpty()) {
                tours.put(seat.hand.id(), new Tour(seat.hand.id(), seat.route));
            }
        }
        List<Unassigned> unassigned = new ArrayList<>(waiting.size());
        overloaded.clear();
        for (UUID id : order) {
            Unassigned nobody = waiting.get(id);
            if (nobody != null) {
                unassigned.add(nobody);
                if (nobody.why() == Unassigned.Reason.PACK_BUSY) {
                    packedBy(id, line);
                }
            }
        }
        return new Schedule(tours, unassigned);
    }

    // The hands with no route whose packs are too full for work waiting on them. Nothing on a route will ever empty
    // such a pack, so the work would wait for good unless they are asked to put their cargo away.
    Set<UUID> overloaded() {
        return Set.copyOf(overloaded);
    }

    private void packedBy(UUID id, List<Seat> line) {
        UUID mustBe = boundWorker(id);
        List<Amount> carried = fixed(id).plan().carrying();
        for (Seat seat : line) {
            if ((mustBe == null || mustBe.equals(seat.hand.id())) && seat.route.isEmpty()
                    && seat.base + cells(seat.with(carried)) > seat.hand.packCapacity()) {
                overloaded.add(seat.hand.id());
            }
        }
    }

    // Work is handed out a wave at a time, each the work most hangs on among what waits on nothing unlaid, as much of
    // it as there are hands: laid breadth first like this, work that could go on side by side meets every hand
    // free, where laid chain by chain each step found the hand that did the last already standing there. A group
    // goes as one piece, since one resident does all of it. Within a wave the work with the least choice of hands
    // picks first. Returns the work tried; work waiting on work that was not laid is left to the caller.
    private Set<UUID> waves(List<UUID> order, List<Seat> line) {
        Map<UUID, Long> hanging = hanging(order);
        Map<UUID, Unit> unitOf = new HashMap<>(order.size() * 2);
        List<Unit> units = new ArrayList<>();
        for (UUID id : order) {
            Unit unit = unitOf.get(id);
            if (unit == null) {
                unit = new Unit(units.size());
                units.add(unit);
                for (UUID member : weave.group(id)) {
                    if (weave.has(member) && !settled.containsKey(member)) {
                        unitOf.putIfAbsent(member, unit);
                    }
                }
                unitOf.putIfAbsent(id, unit);
                unit = unitOf.get(id);
            }
            unit.members.add(id);
            unit.rank = Math.max(unit.rank, hanging.get(id));
        }
        for (Unit unit : units) {
            for (UUID id : unit.members) {
                // Work a resident has begun, or that is theirs alone, is laid before anything else is put in its way.
                if (unit.rank < BEGUN && requiredWorker(id) != null) {
                    unit.rank += BEGUN;
                }
                for (UUID predecessor : weave.before(id)) {
                    if (!settled.containsKey(predecessor) && unitOf.get(predecessor) != unit) {
                        unit.waits++;
                    }
                }
            }
        }
        PriorityQueue<Unit> ready = new PriorityQueue<>(
            Comparator.comparingLong((Unit unit) -> -unit.rank).thenComparingInt(unit -> unit.seq));
        for (Unit unit : units) {
            if (unit.waits == 0 && !unit.members.isEmpty()) {
                ready.add(unit);
            }
        }
        Set<UUID> tried = new HashSet<>();
        List<Unit> wave = new ArrayList<>();
        int width = Math.max(1, line.size());
        while (!ready.isEmpty()) {
            wave.clear();
            while (wave.size() < width && !ready.isEmpty()) {
                Unit unit = ready.poll();
                unit.regret = regret(unit, line);
                wave.add(unit);
            }
            wave.sort(Comparator.comparingInt((Unit unit) -> unit.regret).reversed());
            for (Unit unit : wave) {
                for (UUID id : layUnit(unit, line, tried)) {
                    for (UUID onward : weave.after(id)) {
                        Unit next = unitOf.get(onward);
                        if (next != null && next != unit && --next.waits == 0) {
                            ready.add(next);
                        }
                    }
                }
            }
        }
        return tried;
    }

    // Lays the unit's work in order and returns what was laid. A unit that stops part way with goods still in the
    // pack is taken off again whole, to be tried when what stopped it has changed: left half laid, the hand would
    // carry those goods under every piece of work laid after, until its pack had room for nothing else.
    private List<UUID> layUnit(Unit unit, List<Seat> line, Set<UUID> tried) {
        List<UUID> laid = new ArrayList<>(unit.members.size());
        for (UUID id : unit.members) {
            tried.add(id);
            if (attempt(id, line)) {
                laid.add(id);
                continue;
            }
            Unassigned refused = waiting.get(id);
            if (refused == null || laid.isEmpty() || !loaded(laid)) {
                continue;
            }
            for (UUID back : laid.reversed()) {
                unsettle(seats.get(seatOf.get(back)), back);
                laidNow.remove(back);
                waiting.put(back, new Unassigned(back, refused.why(), id));
            }
            return List.of();
        }
        return laid;
    }

    // Whether this work, done and nothing after it, leaves goods in the pack; work in hand is never taken back.
    private boolean loaded(List<UUID> laid) {
        Map<ItemSpec, Long> held = new HashMap<>();
        for (UUID id : laid) {
            if (heldBy.containsKey(id)) {
                return false;
            }
            for (Amount amount : fixed(id).plan().carrying()) {
                held.merge(amount.spec(), amount.count(), Long::sum);
            }
        }
        for (long count : held.values()) {
            if (count > 0) {
                return true;
            }
        }
        return false;
    }

    private static final class Unit {

        private final List<UUID> members = new ArrayList<>();
        private final int seq;
        private long rank;
        private int waits;
        private int regret;

        Unit(int seq) {
            this.seq = seq;
        }
    }

    // How much unlaid work hangs on each piece, itself counted: the longest chain of it, each step its labor and a
    // walk. Work in a ring, which the order leaves for last, counts what is known of it by then.
    private Map<UUID, Long> hanging(List<UUID> order) {
        Map<UUID, Long> hanging = new HashMap<>(order.size() * 2);
        for (UUID id : order.reversed()) {
            long longest = 0;
            for (UUID onward : weave.after(id)) {
                longest = Math.max(longest, hanging.getOrDefault(onward, 0L));
            }
            hanging.put(id, longest + HOP_TICKS + work(fixed(id).node().spec(), 1.0));
        }
        return hanging;
    }

    // How much later the unit's first work would be done by its second nearest hand than by its nearest, by
    // straight lines: work with one hand for it has everything to lose, work no hand can take has nothing.
    private int regret(Unit unit, List<Seat> line) {
        for (UUID id : unit.members) {
            if (!due(id)) {
                continue;
            }
            List<Candidate> candidates = field(id, line).candidates();
            if (candidates.size() < 2) {
                return candidates.isEmpty() ? 0 : Integer.MAX_VALUE;
            }
            return candidates.get(1).bound() - candidates.get(0).bound();
        }
        return 0;
    }

    // Whether the work is worth trying now: work that waited is tried again only when something it waited on changed.
    private boolean due(UUID id) {
        Unassigned was = waiting.get(id);
        if (was == null) {
            return true;
        }
        Placement plan = fixed(id).plan();
        if (was.why() == Unassigned.Reason.BEYOND_HORIZON) {
            // Time alone brings it nearer: tried once it would be near, or when cut routes may bring it nearer,
            // never more of it at once than the schedule can afford.
            boolean near = now >= beyondUntil.getOrDefault(id, 0) || moved || retry
                || fresh.contains(id) || !plan.equals(seen.get(id));
            return near && beyondTried < MOST_BEYOND_TRIES;
        }
        return retry || moved || !plan.equals(seen.get(id))
            || fresh.contains(id) || anyIn(weave.before(id), laidNow);
    }

    // Tries the work on the routes as they stand, and says whether it was laid.
    private boolean attempt(UUID id, List<Seat> line) {
        if (!due(id)) {
            return false;
        }
        Unassigned was = waiting.remove(id);
        if (was != null && was.why() == Unassigned.Reason.BEYOND_HORIZON) {
            beyondTried++;
        }
        beyondUntil.remove(id);
        seen.put(id, fixed(id).plan());
        Optional<Unassigned> refused = place(id, line, false);
        if (refused.isPresent()) {
            waiting.put(id, refused.get());
            return false;
        }
        laidNow.add(id);
        return true;
    }

    // When the work left past the horizon should next be looked at again, in ticks of the game.
    long wakeAt() {
        int soonest = Integer.MAX_VALUE;
        for (int until : beyondUntil.values()) {
            soonest = Math.min(soonest, until);
        }
        return soonest == Integer.MAX_VALUE || origin < 0L ? Long.MAX_VALUE : origin + Math.max(soonest, now + 1);
    }

    private void index(Crew crew) {
        heldBy.clear();
        for (Crew.Hand hand : crew.hands()) {
            hand.holding().ifPresent(node -> heldBy.put(node, hand.id()));
        }
    }

    private boolean seat(Crew crew) {
        boolean moved = false;
        Set<UUID> here = new HashSet<>();
        int slot = 0;
        for (Crew.Hand hand : crew.hands()) {
            here.add(hand.id());
            Seat seat = seats.get(hand.id());
            if (seat == null) {
                seat = new Seat(hand);
                seats.put(hand.id(), seat);
                moved = true;
            }
            seat.hand = hand;
            seat.slot = slot++;
            seat.resume(now);
        }
        for (Iterator<Seat> all = seats.values().iterator(); all.hasNext(); ) {
            Seat seat = all.next();
            if (here.contains(seat.hand.id())) {
                continue;
            }
            for (UUID id : List.copyOf(seat.route)) {
                unsettle(seat, id);
            }
            all.remove();
            moved = true;
        }
        return moved;
    }

    private boolean cut() {
        Deque<UUID> cutting = new ArrayDeque<>();
        for (Seat seat : seats.values()) {
            for (int at = 0; at < seat.route.size(); at++) {
                UUID id = seat.route.get(at);
                if (!weave.has(id) || !holds(seat, id, at, seat.when.get(at))) {
                    cutting.add(id);
                }
            }
        }
        boolean freed = !cutting.isEmpty();
        cascade(cutting);
        for (Seat seat : seats.values()) {
            repack(seat, cutting);
        }
        freed |= !cutting.isEmpty();
        cascade(cutting);
        return freed;
    }

    private void cascade(Deque<UUID> cutting) {
        while (!cutting.isEmpty()) {
            UUID id = cutting.poll();
            UUID seat = seatOf.get(id);
            if (seat == null) {
                continue;
            }
            unsettle(seats.get(seat), id);
            for (UUID onward : weave.after(id)) {
                if (settled.containsKey(onward)) {
                    cutting.add(onward);
                }
            }
        }
    }

    // A route's loads were counted when its work was laid, against the pack as it was then and the work before it.
    // Once work is cut from the route (it failed, or went), or the pack itself changed, count them again from what
    // the hand carries now, and cut the route from the first work that no longer fits.
    private void repack(Seat seat, Deque<UUID> cutting) {
        int base = seat.hand.packCapacity() - seat.hand.packCells();
        Map<ItemSpec, Long> held = new LinkedHashMap<>();
        for (int at = 0; at < seat.route.size(); at++) {
            UUID id = seat.route.get(at);
            for (Amount amount : fixed(id).plan().carrying()) {
                held.merge(amount.spec(), amount.count(), Long::sum);
            }
            held.values().removeIf(count -> count == 0);
            if (base + cells(held) > seat.hand.packCapacity()) {
                cutting.addAll(seat.route.subList(at, seat.route.size()));
                return;
            }
            Placed was = seat.when.get(at);
            if (was.base() != base || !was.held().equals(held)) {
                Placed fresh = new Placed(was.start(), was.finish(), was.at(), base, held);
                seat.when.set(at, fresh);
                settled.put(id, fresh);
            }
        }
        seat.resume(now);
    }

    private boolean holds(Seat seat, UUID id, int at, Placed placed) {
        Vertex node = fixed(id);
        if (fresh.contains(id) || !node.plan().equals(seen.get(id))) {
            return false;
        }
        if (groupRefusal(seat, id) != null) {
            return false;
        }
        UUID mustBe = boundWorker(id);
        if (mustBe != null && !mustBe.equals(seat.hand.id())) {
            return false;
        }
        if (endless(seat, at) || keeping(seat, id)) {
            return false;
        }
        for (UUID predecessor : weave.before(id)) {
            Placed first = settled.get(predecessor);
            if (first == null || first.finish() > placed.start()) {
                return false;
            }
        }
        return true;
    }

    private Vertex fixed(UUID id) {
        return weave.vertex(id);
    }

    private static boolean anyIn(Set<UUID> ids, Set<UUID> among) {
        if (ids == null) {
            return false;
        }
        for (UUID id : ids) {
            if (among.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private Optional<Unassigned> place(UUID id, List<Seat> seats, boolean quiet) {
        Vertex node = fixed(id);
        NodeSpec spec = node.node().spec();
        Unassigned.Reason worst = Unassigned.Reason.NO_FREE_HAND;
        UUID blocker = null;
        Seat best = null;
        Placed bestPlaced = null;
        // Where on the best hand's route the work goes when not on its end.
        int bestAt = -1;
        // A hand that would stand waiting on work laid elsewhere is wasted for as long, so the wait counts against
        // it again: work that follows on goes to whoever does what it follows, unless another is done that much sooner.
        int bestCost = Integer.MAX_VALUE;

        for (UUID predecessor : weave.before(id)) {
            if (settled.containsKey(predecessor)) {
                continue;
            }
            Unassigned held = waiting.get(predecessor);
            return quiet ? Optional.empty() : Optional.of(new Unassigned(id,
                held == null ? Unassigned.Reason.NO_WAY : held.why(), predecessor));
        }
        UUID mustBe = boundWorker(id);
        List<WorldPos> goals = destinations(node);
        Field field = field(id, seats);
        worst = field.worst();
        boolean anyBound = field.anyBound();
        List<Candidate> candidates = field.candidates();
        List<List<WorldPos>> rest = field.rest();
        // When the rest of the work's group may start at the soonest, for what it waits on laid elsewhere.
        int held = rest.isEmpty() ? 0 : heldUntil(id);
        WorldPos first = goals.isEmpty() ? null : goals.getFirst();
        int pending = 0;
        int joins = 0;
        // The nearest hand whose way there is not known yet: the one way worth asking for.
        Seat waits = null;
        List<WorldPos> waitsFor = List.of();
        // A hand that could not start before the horizon even riding the whole way, on a route deep enough, is not
        // asked the way at all.
        boolean grouped = continuing(id);
        int beyond = Integer.MAX_VALUE;
        for (Candidate candidate : candidates) {
            if (best != null && candidate.bound() >= bestCost) {
                break;
            }
            Seat seat = candidate.seat();
            // Beside work the route already has at the same place the walk there is made once for both, so the
            // work is tried there too, and judged by when it and the rest of its group would be done and by how
            // much later the route then ends.
            // Not when a move is only being tried: taken back, it would leave the route laid out later than it was.
            int at = candidate.join();
            if (at >= 0 && !quiet && joins < MOST_JOINS && !endless(seat, at)
                    && fitsWith(seat, id, node.plan().carrying(), at)) {
                joins++;
                boolean was = changed.contains(seat);
                int before = seat.clock;
                Tail tail = splice(seat, id, at);
                if (tail != null) {
                    int end = seat.clock;
                    int cost = standing(seat, spec) + along(seat, settled.get(id).finish(), rest, held)
                        + end - before;
                    unsplice(seat, tail);
                    if (!was) {
                        changed.remove(seat);
                    }
                    if (past(seat, end) && !grouped) {
                        beyond = Math.min(beyond, end);
                    } else if (best == null || cost < bestCost) {
                        best = seat;
                        bestPlaced = null;
                        bestAt = at;
                        bestCost = cost;
                    }
                    // On the end of the same route it would only walk there again.
                    continue;
                }
                if (!was) {
                    changed.remove(seat);
                }
            }
            if (best != null && candidate.reach() >= bestCost) {
                continue;
            }
            int soonest = soonest(seat, node, goals);
            if (past(seat, soonest) && !grouped && !seat.hand.id().equals(heldBy.get(id))) {
                beyond = Math.min(beyond, soonest);
                continue;
            }
            Fit fit = fit(seat, node, candidate.held());
            if (fit.why() != null) {
                worst = worse(worst, fit.why());
                if (!fit.pending().isEmpty() && waits == null) {
                    waits = seat;
                    waitsFor = fit.pending();
                }
                if (fit.why() == Unassigned.Reason.WAY_PENDING && ++pending >= MOST_PENDING_FARES
                        && best == null) {
                    break;
                }
                continue;
            }
            Placed placed = fit.placed();
            // The rest of the group follows on from here, and waits again for what it follows laid elsewhere: a
            // hand that would bring it there only to stand is as much wasted as one that stands for this work.
            int arrives = placed.finish() + onward(first, rest, seat.hand.pace());
            int cost = standing(seat, spec) + fit.idle() + Math.max(arrives, held) + Math.max(0, held - arrives);
            if (best == null || cost < bestCost || cost == bestCost && tier(seat, spec) < tier(best, spec)) {
                best = seat;
                bestPlaced = placed;
                bestAt = -1;
                bestCost = cost;
            }
        }
        // Weighing the hands asked for no way: a hand that might have done it sooner, had its way been known, has
        // that way asked for now.
        if (waits != null && !quiet
                && sought.add(new Route(waits.hand.who().kind(), waits.cursor, waitsFor))) {
            ways.journey(waits.hand.who(), waits.cursor, new LinkedHashSet<>(waitsFor), Urgency.PLANNING);
        }
        if (best == null && worst == Unassigned.Reason.PACK_BUSY && insert(id, seats, mustBe)) {
            return Optional.empty();
        }
        if (bestAt >= 0) {
            if (splice(best, id, bestAt) != null) {
                return Optional.empty();
            }
            return quiet ? Optional.empty() : Optional.of(new Unassigned(id, worst, blocker));
        }
        boolean late = best != null && past(best, bestPlaced.start()) && !grouped;
        // Past the horizon for every hand that could take it, and no nearer hand waiting on a way or a free spot.
        boolean onlyLate = best == null && beyond != Integer.MAX_VALUE && worst == Unassigned.Reason.NO_FREE_HAND;
        if (late || onlyLate) {
            if (late) {
                beyond = Math.min(beyond, bestPlaced.start());
            }
            if (!quiet) {
                beyondUntil.put(id, beyond - horizon);
            }
            return quiet ? Optional.empty() : Optional.of(new Unassigned(id, Unassigned.Reason.BEYOND_HORIZON));
        }
        if (best == null) {
            if (mustBe != null && !anyBound) {
                worst = Unassigned.Reason.WORKER_AWAY;
                blocker = mustBe;
            }
            return quiet ? Optional.empty() : Optional.of(new Unassigned(id, worst, blocker));
        }
        settle(best, id, bestPlaced);
        return Optional.empty();
    }

    // Work starting then is past the horizon for this hand: later than the horizon, and joining a route already deep.
    private boolean past(Seat seat, int start) {
        return start > now + horizon && seat.route.size() >= depth;
    }

    // Work that does not fit on the end of any route for the pack it would fill may still fit earlier on one, before
    // the goods that fill the pack are picked up: the worker would otherwise hold them waiting on work it has no
    // room left to do. Try each place from the end back, laying the route out again from there, and keep the first
    // where every step still fits; the work in hand stays first.
    private boolean insert(UUID id, List<Seat> seats, UUID mustBe) {
        List<Amount> carried = fixed(id).plan().carrying();
        int laid = 0;
        for (Seat seat : seats) {
            if (mustBe != null && !mustBe.equals(seat.hand.id())) {
                continue;
            }
            if (keeping(seat, id) || groupRefusal(seat, id) != null) {
                continue;
            }
            int head = !seat.route.isEmpty() && seat.hand.id().equals(heldBy.get(seat.route.getFirst())) ? 1 : 0;
            for (int at = seat.route.size() - 1; at >= head; at--) {
                if (endless(seat, at) || !fitsWith(seat, id, carried, at)) {
                    continue;
                }
                if (laid++ >= MOST_INSERTS) {
                    return false;
                }
                Tail tail = splice(seat, id, at);
                if (tail == null) {
                    continue;
                }
                if (settled.get(id).start() <= now + horizon || continuing(id)) {
                    return true;
                }
                unsplice(seat, tail);
            }
        }
        return false;
    }

    // The route from `at` on as it was before work was put in there.
    private record Tail(int at, List<UUID> ids, List<Placed> placed) {
    }

    // Puts the work in at `at` on the seat's route and lays the rest of the route out again after it. Returns the
    // route as it was from there, to put back with `unsplice`; null, with the route untouched, when the work or
    // anything after it no longer fits.
    private Tail splice(Seat seat, UUID id, int at) {
        Tail tail = new Tail(at, List.copyOf(seat.route.subList(at, seat.route.size())),
            List.copyOf(seat.when.subList(at, seat.when.size())));
        strip(seat, at);
        boolean laid = refit(seat, id);
        for (int next = 0; laid && next < tail.ids().size(); next++) {
            laid = refit(seat, tail.ids().get(next));
        }
        if (laid) {
            return tail;
        }
        unsplice(seat, tail);
        return null;
    }

    private void unsplice(Seat seat, Tail tail) {
        strip(seat, tail.at());
        for (int back = 0; back < tail.ids().size(); back++) {
            settle(seat, tail.ids().get(back), tail.placed().get(back));
        }
    }

    // Where on the seat's route work done from `goals` would go beside work done from one of the same cells: after
    // the last such, when that is near the route's end and not the end itself; -1 otherwise.
    private static int joinAt(Seat seat, List<WorldPos> goals) {
        if (!stoodAt(seat, goals)) {
            return -1;
        }
        int size = seat.route.size();
        for (int at = size; at >= 1 && size - at <= MOST_TAIL; at--) {
            if (goals.contains(seat.when.get(at - 1).at().cell())) {
                return at == size ? -1 : at;
            }
        }
        return -1;
    }

    // Where the rest of the work's group is done, as far as it is counted: one resident does all of it, so whoever
    // takes this work walks on to that.
    private List<List<WorldPos>> rest(UUID id) {
        Set<UUID> group = weave.group(id);
        if (group.size() < 2) {
            return List.of();
        }
        List<List<WorldPos>> rest = new ArrayList<>();
        for (UUID member : group) {
            if (!member.equals(id) && weave.has(member) && !settled.containsKey(member)) {
                rest.add(destinations(fixed(member)));
                if (rest.size() == MOST_ONWARD) {
                    break;
                }
            }
        }
        return rest;
    }

    // When the rest of the work's group may start at the soonest, for the work laid already that it waits on.
    private int heldUntil(UUID id) {
        int until = 0;
        int counted = 0;
        for (UUID member : weave.group(id)) {
            if (!member.equals(id) && weave.has(member) && !settled.containsKey(member)) {
                until = Math.max(until, earliestAfter(member));
                if (++counted == MOST_ONWARD) {
                    break;
                }
            }
        }
        return until;
    }

    // When the work, done at `done` on this seat's route as it now stands, and the rest of its group would be done:
    // each of the rest beside work the route has at its place, or else on from the route's end, and not before
    // `held`; a wait for that counts again.
    private int along(Seat seat, int done, List<List<WorldPos>> rest, int held) {
        int whole = done;
        for (List<WorldPos> goals : rest) {
            if (goals.isEmpty()) {
                continue;
            }
            int there = lastAt(seat, goals);
            int arrives = there >= 0 ? there : seat.clock
                + (int) Math.round(crow(seat.cursor, goals) / Math.max(0.1, seat.hand.pace()));
            whole = Math.max(whole, Math.max(arrives, held) + Math.max(0, held - arrives));
        }
        return whole;
    }

    // When the last work near the end of the seat's route done from one of these cells is done, -1 for none.
    private static int lastAt(Seat seat, List<WorldPos> goals) {
        if (!stoodAt(seat, goals)) {
            return -1;
        }
        int size = seat.route.size();
        for (int at = size; at >= 1 && size - at <= MOST_TAIL; at--) {
            if (goals.contains(seat.when.get(at - 1).at().cell())) {
                return seat.when.get(at - 1).finish();
            }
        }
        return -1;
    }

    // The walk from `from` on through `rest`, by straight lines.
    private static int onward(WorldPos from, List<List<WorldPos>> rest, double pace) {
        if (from == null || rest.isEmpty()) {
            return 0;
        }
        int ticks = 0;
        for (List<WorldPos> goals : rest) {
            if (goals.isEmpty()) {
                continue;
            }
            ticks += crow(from, goals);
            from = goals.getFirst();
        }
        return (int) Math.round(ticks / Math.max(0.1, pace));
    }

    private static boolean stoodAt(Seat seat, List<WorldPos> goals) {
        if (seat.stood.isEmpty()) {
            return false;
        }
        for (WorldPos goal : goals) {
            if (seat.stood.containsKey(goal)) {
                return true;
            }
        }
        return false;
    }

    // Whether some other work of the node's group is laid already: the rest of what one resident began is laid
    // with it, however far out, so that a group is never left half handed out.
    private boolean continuing(UUID id) {
        for (UUID member : weave.group(id)) {
            if (!member.equals(id) && settled.containsKey(member)) {
                return true;
            }
        }
        return false;
    }

    // Whether the seat's pack holds its route with `id` put in at `at`, counted from what the hand carries now.
    private boolean fitsWith(Seat seat, UUID id, List<Amount> carried, int at) {
        List<List<Amount>> order = new ArrayList<>(seat.route.size() + 1);
        for (int step = 0; step < seat.route.size(); step++) {
            if (step == at) {
                order.add(carried);
            }
            order.add(fixed(seat.route.get(step)).plan().carrying());
        }
        return PackLoad.fits(seat.hand.packCapacity() - seat.hand.packCells(), seat.hand.packCapacity(), order);
    }

    // `join` is where on the seat's route the work could go beside work done from one of the same cells, -1 for
    // nowhere but the end; `reach` is the least it could cost on the route's end, `bound` the least it could cost
    // at either.
    private record Candidate(Seat seat, Map<ItemSpec, Long> held, int bound, int reach, int join) {
    }

    // The hands that could take the work on the end of their routes, soonest first by straight lines, the worst
    // that kept any other from it, whether any hand it is bound to is among them at all, and where the rest of its
    // group is done.
    private record Field(List<Candidate> candidates, Unassigned.Reason worst, boolean anyBound,
                         List<List<WorldPos>> rest) {
    }

    private Field field(UUID id, List<Seat> seats) {
        Vertex node = fixed(id);
        NodeSpec spec = node.node().spec();
        Unassigned.Reason worst = Unassigned.Reason.NO_FREE_HAND;
        UUID mustBe = boundWorker(id);
        boolean anyBound = false;
        List<Amount> carried = node.plan().carrying();
        List<WorldPos> goals = destinations(node);
        List<List<WorldPos>> rest = rest(id);
        WorldPos first = goals.isEmpty() ? null : goals.getFirst();
        List<Candidate> candidates = new ArrayList<>();
        for (Seat seat : seats) {
            if (mustBe != null && !mustBe.equals(seat.hand.id())) {
                continue;
            }
            anyBound = true;
            if (endless(seat, seat.route.size()) || keeping(seat, id)) {
                continue;
            }
            Unassigned.Reason groupRefusal = groupRefusal(seat, id);
            if (groupRefusal != null) {
                worst = worse(worst, groupRefusal);
                continue;
            }
            Map<ItemSpec, Long> held = seat.with(carried);
            int load = seat.base + cells(held);
            if (load > seat.hand.packCapacity()) {
                // Too full now only waits for the pack to empty; too big for the pack at all never fits.
                worst = worse(worst, cells(pickedUp(carried)) > seat.hand.packCapacity()
                    ? Unassigned.Reason.PACK_FULL : Unassigned.Reason.PACK_BUSY);
                continue;
            }
            // Beside work already at its place the work costs the route no more than its own doing, at the least.
            int join = joinAt(seat, goals);
            int reach = standing(seat, spec)
                + seat.clock + (int) Math.round((crow(seat.cursor, goals) / RIDE_SPEEDUP + labor(seat, node, 1.0))
                / Math.max(0.1, seat.hand.pace())) + onward(first, rest, seat.hand.pace());
            // Beside work at its place it is done no sooner than that work; and when none of the rest of its group
            // has a place on the route, the group is done no sooner than the route ends. So the hand that has the
            // places of both is weighed first.
            int bound = reach;
            if (join >= 0) {
                boolean both = rest.isEmpty();
                for (List<WorldPos> onward : rest) {
                    both |= stoodAt(seat, onward);
                }
                bound = Math.min(reach, standing(seat, spec) + (both ? seat.when.get(join - 1).finish() : seat.clock));
            }
            candidates.add(new Candidate(seat, held, bound, reach, join));
        }
        candidates.sort(Comparator.comparingInt(Candidate::bound));
        return new Field(candidates, worst, anyBound, rest);
    }

    // `idle` is how long the hand would stand at the work before it may start, waiting on work laid elsewhere.
    private record Fit(Placed placed, Unassigned.Reason why, int idle, List<WorldPos> pending) {

        Fit(Unassigned.Reason why, List<WorldPos> pending) {
            this(null, why, 0, pending);
        }
    }

    // Where and when the node would go on the end of this seat's route, or why it cannot.
    private Fit fit(Seat seat, Vertex node, Map<ItemSpec, Long> held) {
        List<WorldPos> goals = destinations(node);
        Faring fare = travel(seat, node, goals);
        if (!(fare instanceof Faring.Yes)) {
            return new Fit(fare instanceof Faring.Later
                ? Unassigned.Reason.WAY_PENDING : Unassigned.Reason.NO_WAY,
                fare instanceof Faring.Later ? goals : List.of());
        }
        Unassigned.Reason why = Unassigned.Reason.NO_WAY;
        List<WorldPos> pending = List.of();
        // A journey to a set only promises a way to one member. The stand reserved, its time and the next
        // journey's origin must all follow the same, individually known way.
        for (WorldPos goal : goals.isEmpty() ? List.of(seat.cursor) : goals) {
            Faring exact = goals.size() <= 1 ? fare : travel(seat, node, List.of(goal));
            if (!(exact instanceof Faring.Yes(Journey by))) {
                if (exact instanceof Faring.Later) {
                    why = worse(why, Unassigned.Reason.WAY_PENDING);
                    if (pending.isEmpty()) {
                        pending = List.of(goal);
                    }
                }
                continue;
            }
            int there = seat.clock + ticks(by, seat.hand.pace());
            int start = Math.max(there, earliestAfter(node.id()));
            int finish = start + labor(seat, node, seat.hand.pace());
            Stand at = new Stand(goal);
            if (!node.plan().stands().isEmpty() && !free(at, new Span(start, finish))) {
                why = worse(why, Unassigned.Reason.NO_FREE_STAND);
                continue;
            }
            return new Fit(new Placed(start, finish, at, seat.base, held), null, start - there, List.of());
        }
        return new Fit(why, pending);
    }

    // Work that joins the work before it on the route shares that work's wind-up.
    private int labor(Seat seat, Vertex node, double pace) {
        return seat.route.isEmpty() ? work(node.node().spec(), pace)
            : labor(fixed(seat.route.getLast()), node, pace);
    }

    private int labor(Vertex done, Vertex node, double pace) {
        return done != null && node.node().joins(done.node()) ? 1 : work(node.node().spec(), pace);
    }

    // Work held until a condition breaks, like keeping a post, has no end to plan anything after.
    private boolean endless(Seat seat, int before) {
        return seat.endless < before;
    }

    private boolean continuous(UUID id) {
        Vertex node = fixed(id);
        return node != null && node.node().spec().workload() instanceof Workload.Continuous;
    }

    // A hand already holding such work, even work no longer on its route, is not free for anything else.
    private boolean keeping(Seat seat, UUID id) {
        Optional<UUID> held = seat.hand.holding().filter(node -> !node.equals(id));
        Vertex holding = held.map(this::fixed).orElse(null);
        return holding != null && holding.node().spec().workload() instanceof Workload.Continuous;
    }

    // The earliest the seat could start the node: straight there at riding speed, and not before what comes first.
    private int soonest(Seat seat, Vertex node, List<WorldPos> goals) {
        int there = (int) (crow(seat.cursor, goals) / RIDE_SPEEDUP / Math.max(1.0, seat.hand.pace()));
        return Math.max(seat.clock + there, earliestAfter(node.id()));
    }

    private static int standing(Seat seat, NodeSpec spec) {
        return tier(seat, spec) * TIER_TICKS;
    }

    private static int crow(WorldPos from, List<WorldPos> goals) {
        int nearest = Integer.MAX_VALUE;
        for (WorldPos goal : goals) {
            if (!goal.sameRealm(from)) {
                return 0;
            }
            nearest = Math.min(nearest, (int) Math.floor(Math.sqrt(from.cell().distSqr(goal.cell())) / Gait.BLOCKS_PER_TICK));
        }
        return nearest == Integer.MAX_VALUE ? 0 : nearest;
    }

    private static int tier(Seat seat, NodeSpec spec) {
        return spec.vocation().flatMap(seat.hand::keennessOf).orElse(Keenness.DEFAULT).ordinal();
    }

    private void settle(Seat seat, UUID id, Placed placed) {
        if (seat.endless == Integer.MAX_VALUE && continuous(id)) {
            seat.endless = seat.route.size();
        }
        changed.add(seat);
        seat.route.add(id);
        seat.when.add(placed);
        seat.cursor = placed.at().cell();
        seat.clock = placed.finish();
        seat.base = placed.base();
        seat.held = placed.held();
        settled.put(id, placed);
        seatOf.put(id, seat.hand.id());
        spots.computeIfAbsent(placed.at(), key -> new ArrayList<>())
            .add(new Span(placed.start(), placed.finish()));
        seat.stood.merge(placed.at().cell(), 1, Integer::sum);
    }

    private void unsettle(Seat seat, UUID id) {
        int at = seat.route.indexOf(id);
        Placed placed = seat.when.get(at);
        seat.route.remove(at);
        seat.when.remove(at);
        changed.add(seat);
        if (at <= seat.endless) {
            seat.endless = Integer.MAX_VALUE;
            for (int step = 0; step < seat.route.size(); step++) {
                if (continuous(seat.route.get(step))) {
                    seat.endless = step;
                    break;
                }
            }
        }
        settled.remove(id);
        seatOf.remove(id);
        seat.stood.computeIfPresent(placed.at().cell(), (cell, count) -> count == 1 ? null : count - 1);
        List<Span> held = spots.get(placed.at());
        if (held != null) {
            held.remove(new Span(placed.start(), placed.finish()));
            if (held.isEmpty()) {
                spots.remove(placed.at());
            }
        }
        seat.resume(now);
    }

    // Rearrange until no single change ends the plan sooner: hand the last work of the longest route to someone
    // else, or move a short run of work to another place on its own route, which is how a worker stops walking
    // back and forth between the same two places.
    private void improve(List<Seat> seats) {
        Set<Seat> present = new HashSet<>(seats);
        tries = 0;
        boolean better = true;
        while (better && tries < MOST_TRIES) {
            better = shed(seats);
            for (Seat seat : List.copyOf(changed)) {
                while (present.contains(seat) && tries < MOST_TRIES && reorder(seats, seat)) {
                    better = true;
                }
            }
        }
    }

    // Hand work from the end of the longest route to someone else, until no such move ends the plan sooner.
    private boolean shed(List<Seat> seats) {
        boolean better = false;
        for (int moves = 0; moves < MOST_SHEDS; moves++) {
            Seat longest = longest(seats);
            if (longest == null || longest.route.size() < 2 || !shedFrom(seats, longest)) {
                return better;
            }
            better = true;
        }
        return better;
    }

    // Try the last few pieces of the longest route, latest first, and keep the first move that ends the plan
    // sooner. A piece is a whole group, since work one resident must do together goes together; work bound to
    // this worker, or that work laid elsewhere waits on, stays.
    private boolean shedFrom(List<Seat> seats, Seat longest) {
        List<Seat> others = new ArrayList<>(seats);
        others.remove(longest);
        if (others.isEmpty()) {
            return false;
        }
        Score was = score(seats);
        int head = longest.hand.id().equals(heldBy.get(longest.route.getFirst())) ? 1 : 0;
        Set<UUID> tried = new HashSet<>();
        int looked = 0;
        for (int at = longest.route.size() - 1; at >= head && looked < SHED_LOOKS; at--) {
            UUID last = longest.route.get(at);
            if (tried.contains(last)) {
                continue;
            }
            Set<UUID> group = weave.group(last);
            List<UUID> moving = new ArrayList<>();
            for (UUID id : longest.route) {
                if (group.contains(id)) {
                    moving.add(id);
                }
            }
            tried.addAll(moving);
            looked++;
            int from = longest.route.indexOf(moving.getFirst());
            if (from < head || !movable(moving) || !takenBySomeone(moving, others)) {
                continue;
            }
            List<UUID> oldIds = List.copyOf(longest.route.subList(from, longest.route.size()));
            List<Placed> oldPlaced = List.copyOf(longest.when.subList(from, longest.when.size()));
            strip(longest, from);
            List<UUID> staying = new ArrayList<>(oldIds);
            staying.removeAll(moving);
            boolean laid = lay(seats, longest, staying).isPresent();
            for (UUID id : moving) {
                if (!laid) {
                    break;
                }
                place(id, others, true);
                laid = settled.containsKey(id);
            }
            if (laid && score(seats).beats(was)) {
                return true;
            }
            for (UUID id : moving) {
                UUID took = seatOf.get(id);
                if (took != null && !took.equals(longest.hand.id())) {
                    unsettle(this.seats.get(took), id);
                }
            }
            strip(longest, from);
            for (int back = 0; back < oldIds.size(); back++) {
                settle(longest, oldIds.get(back), oldPlaced.get(back));
            }
        }
        return false;
    }

    // Whether anyone else is fit for the whole piece and free to take it, checked before the route is touched, so
    // that a piece no one could take costs no new ways.
    private boolean takenBySomeone(List<UUID> moving, List<Seat> others) {
        List<NodeSpec> steps = new ArrayList<>(moving.size());
        for (UUID id : moving) {
            steps.add(fixed(id).node().spec());
        }
        for (Seat seat : others) {
            if (!keeping(seat, moving.getFirst()) && !endless(seat, seat.route.size())
                    && Fitness.of(seat.hand, new Fitness.Work(steps, Set.of(), 0)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // Whether these may go to another worker: none is bound to one, and no work outside them waits on them.
    private boolean movable(List<UUID> moving) {
        for (UUID id : moving) {
            if (requiredWorker(id) != null) {
                return false;
            }
            for (UUID onward : weave.after(id)) {
                if (settled.containsKey(onward) && !moving.contains(onward)) {
                    return false;
                }
            }
        }
        return true;
    }

    // Try moving each run of up to a few nodes elsewhere on the route, first by a straight-line guess and only
    // then for real; keep the first move that ends the plan sooner.
    private boolean reorder(List<Seat> seats, Seat seat) {
        int size = seat.route.size();
        int head = !seat.route.isEmpty() && seat.hand.id().equals(heldBy.get(seat.route.getFirst())) ? 1 : 0;
        for (int length = 1; length <= LONGEST_RUN; length++) {
            for (int from = head; from + length <= size; from++) {
                for (int to = head; to + length <= size; to++) {
                    if (to == from) {
                        continue;
                    }
                    if (guess(seat, from, length, to) > -SAVING) {
                        continue;
                    }
                    List<UUID> order = moved(seat.route, from, length, to);
                    if (declined.contains(order)) {
                        continue;
                    }
                    if (tries++ >= MOST_TRIES) {
                        return false;
                    }
                    if (replay(seats, seat, order, Math.min(from, to))) {
                        return true;
                    }
                    decline(order);
                }
            }
        }
        return false;
    }

    private void decline(List<UUID> order) {
        if (declined.size() >= MOST_DECLINED) {
            declined.clear();
        }
        declined.add(List.copyOf(order));
    }

    private static List<UUID> moved(List<UUID> route, int from, int length, int to) {
        List<UUID> order = new ArrayList<>(route);
        List<UUID> run = new ArrayList<>(order.subList(from, from + length));
        order.subList(from, from + length).clear();
        order.addAll(to, run);
        return order;
    }

    // How much straight-line walking and winding up the move would save, counting only the steps it changes,
    // to rule out moves not worth planning for real. Negative is better.
    private double guess(Seat seat, int from, int length, int to) {
        List<UUID> route = seat.route;
        UUID first = route.get(from);
        UUID last = route.get(from + length - 1);
        UUID before = from == 0 ? null : route.get(from - 1);
        UUID after = from + length < route.size() ? route.get(from + length) : null;
        double change = -step(seat, before, first);
        if (after != null) {
            change += step(seat, before, after) - step(seat, last, after);
        }
        UUID into = to == 0 ? null : without(route, from, length, to - 1);
        UUID onto = to < route.size() - length ? without(route, from, length, to) : null;
        change += step(seat, into, first);
        if (onto != null) {
            change += step(seat, last, onto) - step(seat, into, onto);
        }
        return change;
    }

    private static UUID without(List<UUID> route, int from, int length, int at) {
        return route.get(at < from ? at : at + length);
    }

    // Unrounded, so that going straight never looks longer than a detour.
    private double step(Seat seat, UUID done, UUID next) {
        double pace = Math.max(0.1, seat.hand.pace());
        WorldPos at = done == null ? seat.hand.at() : where(done, seat.hand.at());
        WorldPos to = where(next, at);
        double walk = to.sameRealm(at) ? Math.sqrt(at.cell().distSqr(to.cell())) / Gait.BLOCKS_PER_TICK : 0;
        return walk / pace + labor(done == null ? null : fixed(done), fixed(next), pace);
    }

    private WorldPos where(UUID id, WorldPos otherwise) {
        Placed placed = settled.get(id);
        return placed == null ? otherwise : placed.at().cell();
    }

    // Lay the route out again in the new order from the given place on; keep it if every node still fits and the
    // plan ends sooner than the old order laid out afresh, otherwise put the old layout back exactly. The old
    // layout's own times may be stale, and any fresh layout would beat those.
    private boolean replay(List<Seat> seats, Seat seat, List<UUID> order, int from) {
        List<UUID> oldIds = List.copyOf(seat.route.subList(from, seat.route.size()));
        List<Placed> oldPlaced = List.copyOf(seat.when.subList(from, seat.when.size()));
        strip(seat, from);
        Optional<Score> was = lay(seats, seat, oldIds);
        strip(seat, from);
        Optional<Score> now = was.isEmpty() ? Optional.empty() : lay(seats, seat, order.subList(from, order.size()));
        if (now.isPresent() && now.get().beats(was.get())) {
            return true;
        }
        strip(seat, from);
        for (int at = 0; at < oldIds.size(); at++) {
            settle(seat, oldIds.get(at), oldPlaced.get(at));
        }
        return false;
    }

    private Optional<Score> lay(List<Seat> seats, Seat seat, List<UUID> order) {
        for (UUID id : order) {
            if (!refit(seat, id)) {
                return Optional.empty();
            }
        }
        return Optional.of(score(seats));
    }

    private void strip(Seat seat, int from) {
        while (seat.route.size() > from) {
            unsettle(seat, seat.route.getLast());
        }
    }

    private boolean refit(Seat seat, UUID id) {
        Vertex node = fixed(id);
        for (UUID predecessor : weave.before(id)) {
            if (!settled.containsKey(predecessor)) {
                return false;
            }
        }
        if (endless(seat, seat.route.size())) {
            return false;
        }
        Map<ItemSpec, Long> held = seat.with(node.plan().carrying());
        if (seat.base + cells(held) > seat.hand.packCapacity()) {
            return false;
        }
        Fit fit = fit(seat, node, held);
        if (fit.why() != null) {
            return false;
        }
        for (UUID onward : weave.after(id)) {
            Placed later = settled.get(onward);
            if (later != null && later.start() < fit.placed().finish()) {
                return false;
            }
        }
        settle(seat, id, fit.placed());
        return true;
    }

    // Plans compare by when the last worker is done, then by when all of them are, so a shorter route counts even
    // when it is not the longest one.
    private record Score(int end, long total) {

        boolean beats(Score other) {
            return end != other.end ? end < other.end : total < other.total;
        }
    }

    private static Score score(List<Seat> seats) {
        int end = 0;
        long total = 0;
        for (Seat seat : seats) {
            end = Math.max(end, seat.clock);
            total += seat.clock;
        }
        return new Score(end, total);
    }

    private static Seat longest(List<Seat> seats) {
        Seat longest = null;
        for (Seat seat : seats) {
            if (!seat.route.isEmpty() && (longest == null || seat.clock > longest.clock)) {
                longest = seat;
            }
        }
        return longest;
    }

    private UUID boundWorker(UUID id) {
        for (UUID member : weave.group(id)) {
            UUID hard = requiredWorker(member);
            if (hard != null) return hard;
        }
        for (UUID member : weave.group(id)) {
            UUID seat = seatOf.get(member);
            if (seat != null) return seat;
        }
        return null;
    }

    private UUID requiredWorker(UUID id) {
        UUID holder = heldBy.get(id);
        if (holder != null) return holder;
        UUID pinned = weave.pin(id);
        if (pinned != null) return pinned;
        return fixed(id).node().worker().orElse(null);
    }

    private static final Unassigned.Reason[] REASONS = Unassigned.Reason.values();

    // Asked of every hand for every piece of work, more than once a schedule, and the same each time within one.
    private Unassigned.Reason groupRefusal(Seat seat, UUID id) {
        byte[] known = fits.computeIfAbsent(id, key -> new byte[seats.size()]);
        if (seat.slot >= known.length) {
            return unfit(seat, id);
        }
        if (known[seat.slot] == 0) {
            Unassigned.Reason why = unfit(seat, id);
            known[seat.slot] = (byte) (why == null ? 1 : why.ordinal() + 2);
        }
        return known[seat.slot] == 1 ? null : REASONS[known[seat.slot] - 2];
    }

    private Unassigned.Reason unfit(Seat seat, UUID id) {
        Set<UUID> bound = new LinkedHashSet<>();
        List<NodeSpec> steps = new ArrayList<>();
        for (UUID member : weave.group(id)) {
            UUID pinned = requiredWorker(member);
            if (pinned != null) {
                bound.add(pinned);
            }
            steps.add(fixed(member).node().spec());
        }
        return Fitness.of(seat.hand, new Fitness.Work(steps, bound, 0)).map(unfit -> switch (unfit) {
            case BOUND_ELSEWHERE -> Unassigned.Reason.SAME_WORKER_SPLIT;
            case NO_LICENCE -> Unassigned.Reason.NO_LICENCE;
            case NO_TOOL -> Unassigned.Reason.NO_TOOL;
            case NO_ROOM -> Unassigned.Reason.PACK_FULL;
        }).orElse(null);
    }

    private Faring travel(Seat seat, Vertex node, List<WorldPos> goals) {
        if (goals.isEmpty() || seat.hand.id().equals(heldBy.get(node.id())) && goals.contains(seat.cursor)) {
            return Faring.yes(new Journey(0, 0, List.of()));
        }
        Route route = new Route(seat.hand.who().kind(), seat.cursor, goals);
        Faring known = fares.get(route);
        if (known != null) {
            return known;
        }
        Faring fare = ways.reckon(seat.hand.who(), seat.cursor, new LinkedHashSet<>(goals));
        if (fares.size() == MAX_FARES) {
            fares.remove(fares.keySet().iterator().next());
        }
        fares.put(route, fare);
        return fare;
    }

    private static List<WorldPos> destinations(Vertex node) {
        Set<WorldPos> goals = new LinkedHashSet<>();
        for (Stand stand : node.plan().stands()) {
            goals.add(stand.cell());
        }
        if (goals.isEmpty()) {
            goals.addAll(node.node().spec().stances().cells());
        }
        return List.copyOf(goals);
    }

    private boolean free(Stand at, Span when) {
        for (Span held : spots.getOrDefault(at, List.of())) {
            if (held.overlaps(when)) {
                return false;
            }
        }
        return true;
    }

    private int earliestAfter(UUID id) {
        int soonest = 0;
        for (UUID predecessor : weave.before(id)) {
            Placed placed = settled.get(predecessor);
            if (placed != null) {
                soonest = Math.max(soonest, placed.finish());
            }
        }
        return soonest;
    }

    private static int ticks(Journey by, double pace) {
        return (int) Math.round(by.walkTicks() / Math.max(0.1, pace)) + by.rideTicks();
    }

    private static int work(NodeSpec spec, double pace) {
        int base = spec.workload() instanceof Workload.Once once
            ? once.baseTicks()
            : spec.estimate();
        return (int) Math.round(Math.max(1, base) / Math.max(0.1, pace * spec.pace()));
    }

    private static int cells(Map<ItemSpec, Long> carrying) {
        return PackLoad.cells(carrying);
    }

    private static Map<ItemSpec, Long> pickedUp(List<Amount> carrying) {
        Map<ItemSpec, Long> up = new LinkedHashMap<>();
        for (Amount amount : carrying) {
            if (amount.count() > 0) {
                up.merge(amount.spec(), amount.count(), Long::sum);
            }
        }
        return up;
    }

    private static Unassigned.Reason worse(Unassigned.Reason standing, Unassigned.Reason found) {
        return progress(found) > progress(standing) ? found : standing;
    }

    private static int progress(Unassigned.Reason why) {
        return switch (why) {
            case NO_FREE_HAND -> 0;
            case NO_LICENCE -> 1;
            case NO_TOOL -> 2;
            case PACK_FULL -> 3;
            case PACK_BUSY -> 4;
            case NO_WAY -> 5;
            case WAY_PENDING -> 6;
            case CLAIM_TAKEN -> 7;
            case SAME_WORKER_SPLIT -> 8;
            case WORKER_AWAY -> 9;
            case NO_FREE_STAND -> 10;
            case BEYOND_HORIZON -> 11;
        };
    }

    // The work not yet laid on any route, each after whatever unlaid work it waits on, in the order it came to the
    // plan otherwise: what is laid already holds nothing back.
    private List<UUID> topological() {
        Map<UUID, Integer> waitingOn = new LinkedHashMap<>();
        for (UUID id : weave.vertices().keySet()) {
            if (!settled.containsKey(id)) {
                waitingOn.put(id, 0);
            }
        }
        for (UUID id : waitingOn.keySet()) {
            for (UUID onward : weave.after(id)) {
                waitingOn.computeIfPresent(onward, (key, count) -> count + 1);
            }
        }
        Deque<UUID> ready = new ArrayDeque<>();
        waitingOn.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        Set<UUID> sorted = new LinkedHashSet<>(waitingOn.size());
        while (!ready.isEmpty()) {
            UUID id = ready.removeFirst();
            sorted.add(id);
            for (UUID onward : weave.after(id)) {
                if (waitingOn.containsKey(onward) && waitingOn.merge(onward, -1, Integer::sum) == 0) {
                    ready.addFirst(onward);
                }
            }
        }
        sorted.addAll(waitingOn.keySet());
        return new ArrayList<>(sorted);
    }

    void closed() {
        seats.clear();
        spots.clear();
        settled.clear();
        seatOf.clear();
        waiting.clear();
        seen.clear();
        beyondUntil.clear();
        declined.clear();
        fits.clear();
        laidNow.clear();
        fares.clear();
        sought.clear();
        changed.clear();
        origin = -1L;
    }
}
