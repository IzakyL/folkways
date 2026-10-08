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
import io.github.izakyl.folkways.core.api.work.Stances;
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
    // How many pieces of work one schedule may hand off the longest route, and how many pieces from its end each
    // hand-off looks at before giving up.
    private static final int MOST_SHEDS = 64;
    private static final int SHED_LOOKS = 8;

    // How far past now work is handed out; see FolkwaysConfig.planHorizonTicks.
    private final int horizon;

    private final Map<UUID, Seat> seats = new LinkedHashMap<>();
    private final Map<Stand, List<Span>> spots = new LinkedHashMap<>();
    private final Map<UUID, Placed> settled = new LinkedHashMap<>();
    private final Map<UUID, UUID> seatOf = new LinkedHashMap<>();
    private final Map<UUID, Unassigned> waiting = new LinkedHashMap<>();
    private final Map<UUID, Placement> seen = new LinkedHashMap<>();
    private long origin = -1L;

    private Weave weave = Weave.EMPTY;
    private Ways ways;
    private int now;
    private final Map<UUID, Set<UUID>> after = new HashMap<>();
    private final Map<UUID, Set<UUID>> before = new HashMap<>();
    private final Map<UUID, UUID> heldBy = new HashMap<>();
    private final Map<UUID, Set<UUID>> groups = new HashMap<>();
    private final Map<Route, Faring> fares = new LinkedHashMap<>();
    private int tries;
    // Orders that looked shorter by straight lines but were not once laid out for real, so later schedules do
    // not ask for the same ways again.
    private final Set<List<UUID>> declined = new HashSet<>();

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
            Map<ItemSpec, Long> after = new LinkedHashMap<>(held);
            for (Amount amount : carrying) {
                after.merge(amount.spec(), amount.count(), Long::sum);
            }
            after.values().removeIf(count -> count == 0);
            return after;
        }
    }

    Tours() {
        this(FolkwaysConfig.planHorizonTicks());
    }

    Tours(int horizon) {
        this.horizon = horizon;
    }

    int horizon() {
        return horizon;
    }

    Schedule schedule(Weave weave, Crew crew, Ways ways, long tick, boolean retry) {
        if (origin < 0L) {
            origin = tick;
        }
        this.now = (int) Math.clamp(tick - origin, 0L, Integer.MAX_VALUE);
        this.weave = weave;
        this.ways = ways;
        fares.clear();
        index(crew);

        boolean moved = seat(crew);
        moved |= cut();
        List<Seat> line = new ArrayList<>(crew.hands().size());
        for (Crew.Hand hand : crew.hands()) {
            line.add(seats.get(hand.id()));
        }
        waiting.keySet().retainAll(weave.vertices().keySet());

        List<UUID> order = topological();
        Set<UUID> placedNow = new HashSet<>();
        for (UUID id : order) {
            if (settled.containsKey(id)) {
                continue;
            }
            Placement plan = fixed(id).plan();
            // Work left past the horizon is tried again every plan: time alone brings it nearer.
            if (waiting.containsKey(id) && waiting.get(id).why() != Unassigned.Reason.BEYOND_HORIZON
                    && !retry && !moved && plan.equals(seen.get(id))
                    && !weave.fresh().contains(id) && !anyIn(after.get(id), placedNow)) {
                continue;
            }
            waiting.remove(id);
            seen.put(id, plan);
            place(id, line, false).ifPresentOrElse(
                refused -> waiting.put(id, refused),
                () -> placedNow.add(id));
        }
        seen.keySet().removeIf(id -> !settled.containsKey(id) && !waiting.containsKey(id));
        if (!placedNow.isEmpty()) {
            improve(line);
        }

        Map<UUID, Tour> tours = new LinkedHashMap<>();
        for (Seat seat : line) {
            if (!seat.route.isEmpty()) {
                tours.put(seat.hand.id(), new Tour(seat.hand.id(), seat.route));
            }
        }
        List<Unassigned> unassigned = new ArrayList<>(waiting.size());
        for (UUID id : order) {
            Unassigned nobody = waiting.get(id);
            if (nobody != null) {
                unassigned.add(nobody);
            }
        }
        return new Schedule(tours, unassigned);
    }

    private void index(Crew crew) {
        after.clear();
        before.clear();
        heldBy.clear();
        for (Before link : weave.orders()) {
            after.computeIfAbsent(link.to(), key -> new LinkedHashSet<>()).add(link.from());
            before.computeIfAbsent(link.from(), key -> new LinkedHashSet<>()).add(link.to());
        }
        for (Crew.Hand hand : crew.hands()) {
            hand.holding().ifPresent(node -> heldBy.put(node, hand.id()));
        }
        groups.clear();
        for (Set<UUID> group : weave.groups()) {
            group.forEach(member -> groups.put(member, group));
        }
    }

    private boolean seat(Crew crew) {
        boolean moved = false;
        Set<UUID> here = new HashSet<>();
        for (Crew.Hand hand : crew.hands()) {
            here.add(hand.id());
            Seat seat = seats.get(hand.id());
            if (seat == null) {
                seat = new Seat(hand);
                seats.put(hand.id(), seat);
                moved = true;
            }
            seat.hand = hand;
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
                if (!weave.vertices().containsKey(id) || !holds(seat, id, seat.when.get(at))) {
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
            for (UUID onward : before.getOrDefault(id, Set.of())) {
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

    private boolean holds(Seat seat, UUID id, Placed placed) {
        Vertex node = fixed(id);
        NodeSpec spec = node.node().spec();
        if (weave.fresh().contains(id) || !node.plan().equals(seen.get(id))) {
            return false;
        }
        if (groupRefusal(seat, id) != null) {
            return false;
        }
        UUID mustBe = boundWorker(id);
        if (mustBe != null && !mustBe.equals(seat.hand.id())) {
            return false;
        }
        if (endless(seat, seat.route.indexOf(id)) || keeping(seat, id)) {
            return false;
        }
        for (UUID predecessor : after.getOrDefault(id, Set.of())) {
            if (!weave.vertices().containsKey(predecessor)) {
                continue;
            }
            Placed first = settled.get(predecessor);
            if (first == null || first.finish() > placed.start()) {
                return false;
            }
        }
        return true;
    }

    private Vertex fixed(UUID id) {
        return weave.vertices().get(id);
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

        for (UUID predecessor : after.getOrDefault(id, Set.of())) {
            if (!weave.vertices().containsKey(predecessor) || settled.containsKey(predecessor)) {
                continue;
            }
            Unassigned held = waiting.get(predecessor);
            return quiet ? Optional.empty() : Optional.of(new Unassigned(id,
                held == null ? Unassigned.Reason.NO_WAY : held.why(), predecessor));
        }
        UUID mustBe = boundWorker(id);
        boolean anyBound = false;
        List<Amount> carried = node.plan().carrying();
        List<WorldPos> goals = destinations(node);
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
            candidates.add(new Candidate(seat, held, standing(seat, spec)
                + seat.clock + (int) Math.round((crow(seat.cursor, goals) / RIDE_SPEEDUP + labor(seat, node, 1.0))
                / Math.max(0.1, seat.hand.pace()))));
        }
        candidates.sort(Comparator.comparingInt(Candidate::bound));
        int pending = 0;
        for (Candidate candidate : candidates) {
            if (bestPlaced != null && candidate.bound() >= standing(best, spec) + bestPlaced.finish()) {
                break;
            }
            Seat seat = candidate.seat();
            Fit fit = fit(seat, node, candidate.held());
            if (fit.why() != null) {
                worst = worse(worst, fit.why());
                if (fit.why() == Unassigned.Reason.WAY_PENDING && ++pending >= MOST_PENDING_FARES
                        && bestPlaced == null) {
                    break;
                }
                continue;
            }
            Placed placed = fit.placed();
            if (bestPlaced == null || keener(seat, placed, best, bestPlaced, spec)) {
                best = seat;
                bestPlaced = placed;
            }
        }
        if (best == null && worst == Unassigned.Reason.PACK_BUSY && insert(id, seats, mustBe)) {
            return Optional.empty();
        }
        if (best != null && bestPlaced.start() > now + horizon && !continuing(id)) {
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
                List<UUID> oldIds = List.copyOf(seat.route.subList(at, seat.route.size()));
                List<Placed> oldPlaced = List.copyOf(seat.when.subList(at, seat.when.size()));
                strip(seat, at);
                List<UUID> order = new ArrayList<>(List.of(id));
                order.addAll(oldIds);
                if (lay(seats, seat, order).isPresent()
                        && (settled.get(id).start() <= now + horizon || continuing(id))) {
                    return true;
                }
                strip(seat, at);
                for (int back = 0; back < oldIds.size(); back++) {
                    settle(seat, oldIds.get(back), oldPlaced.get(back));
                }
            }
        }
        return false;
    }

    // Whether some other work of the node's group is laid already: the rest of what one resident began is laid
    // with it, however far out, so that a group is never left half handed out.
    private boolean continuing(UUID id) {
        for (UUID member : groups.getOrDefault(id, Set.of())) {
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

    private record Candidate(Seat seat, Map<ItemSpec, Long> held, int bound) {
    }

    private record Fit(Placed placed, Unassigned.Reason why) {
    }

    // Where and when the node would go on the end of this seat's route, or why it cannot.
    private Fit fit(Seat seat, Vertex node, Map<ItemSpec, Long> held) {
        Faring fare = travel(seat, node);
        if (!(fare instanceof Faring.Yes(Journey by))) {
            return new Fit(null, fare instanceof Faring.Later
                ? Unassigned.Reason.WAY_PENDING : Unassigned.Reason.NO_WAY);
        }
        int start = Math.max(seat.clock + ticks(by, seat.hand.pace()), earliestAfter(node.id()));
        int finish = start + labor(seat, node, seat.hand.pace());
        Optional<Stand> at = free(seat, node, new Span(start, finish));
        if (at.isEmpty()) {
            return new Fit(null, Unassigned.Reason.NO_FREE_STAND);
        }
        return new Fit(new Placed(start, finish, at.get(), seat.base, held), null);
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
        for (int at = 0; at < before; at++) {
            Vertex held = fixed(seat.route.get(at));
            if (held != null && held.node().spec().workload() instanceof Workload.Continuous) {
                return true;
            }
        }
        return false;
    }

    // A hand already holding such work, even work no longer on its route, is not free for anything else.
    private boolean keeping(Seat seat, UUID id) {
        Optional<UUID> held = seat.hand.holding().filter(node -> !node.equals(id));
        Vertex holding = held.map(this::fixed).orElse(null);
        return holding != null && holding.node().spec().workload() instanceof Workload.Continuous;
    }

    private static boolean keener(Seat seat, Placed placed, Seat standing, Placed had, NodeSpec spec) {
        int mine = standing(seat, spec) + placed.finish();
        int theirs = standing(standing, spec) + had.finish();
        return mine != theirs ? mine < theirs : tier(seat, spec) < tier(standing, spec);
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
    }

    private void unsettle(Seat seat, UUID id) {
        int at = seat.route.indexOf(id);
        Placed placed = seat.when.get(at);
        seat.route.remove(at);
        seat.when.remove(at);
        settled.remove(id);
        seatOf.remove(id);
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
        tries = 0;
        boolean better = true;
        while (better && tries < MOST_TRIES) {
            better = shed(seats);
            for (Seat seat : seats) {
                while (tries < MOST_TRIES && reorder(seats, seat)) {
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
            Set<UUID> group = groups.getOrDefault(last, Set.of(last));
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
            for (UUID onward : before.getOrDefault(id, Set.of())) {
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
        for (UUID predecessor : after.getOrDefault(id, Set.of())) {
            if (weave.vertices().containsKey(predecessor) && !settled.containsKey(predecessor)) {
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
        for (UUID onward : before.getOrDefault(id, Set.of())) {
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
        for (UUID member : groups.getOrDefault(id, Set.of(id))) {
            UUID hard = requiredWorker(member);
            if (hard != null) return hard;
        }
        for (UUID member : groups.getOrDefault(id, Set.of(id))) {
            UUID seat = seatOf.get(member);
            if (seat != null) return seat;
        }
        return null;
    }

    private UUID requiredWorker(UUID id) {
        UUID holder = heldBy.get(id);
        if (holder != null) return holder;
        UUID pinned = weave.pins().get(id);
        if (pinned != null) return pinned;
        return fixed(id).node().worker().orElse(null);
    }

    private Unassigned.Reason groupRefusal(Seat seat, UUID id) {
        Set<UUID> bound = new LinkedHashSet<>();
        List<NodeSpec> steps = new ArrayList<>();
        for (UUID member : groups.getOrDefault(id, Set.of(id))) {
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

    private Faring travel(Seat seat, Vertex node) {
        if (seat.hand.id().equals(heldBy.get(node.id()))) {
            return Faring.yes(new Journey(0, 0, List.of()));
        }
        List<WorldPos> goals = destinations(node);
        if (goals.isEmpty()) {
            return Faring.yes(new Journey(0, 0, List.of()));
        }
        Route route = new Route(seat.hand.who().kind(), seat.cursor, goals);
        Faring known = fares.get(route);
        if (known != null) {
            return known;
        }
        Faring fare = ways.journey(seat.hand.who(), seat.cursor, new LinkedHashSet<>(goals),
            Urgency.planning(weave.rankOf(node.id())));
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

    // Work with nowhere it must stand is done where the worker already is.
    private Optional<Stand> free(Seat seat, Vertex node, Span when) {
        Set<Stand> cells = node.plan().stands();
        if (cells.isEmpty()) {
            return Optional.of(new Stand(node.node().spec().stances() instanceof Stances.Wherever
                ? seat.cursor : node.node().spec().site().where()));
        }
        for (Stand cell : cells) {
            boolean taken = false;
            for (Span held : spots.getOrDefault(cell, List.of())) {
                taken |= held.overlaps(when);
            }
            if (!taken) {
                return Optional.of(cell);
            }
        }
        return Optional.empty();
    }

    private int earliestAfter(UUID id) {
        int soonest = 0;
        for (UUID predecessor : after.getOrDefault(id, Set.of())) {
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

    private List<UUID> topological() {
        Map<UUID, List<UUID>> next = new LinkedHashMap<>();
        Map<UUID, Integer> waitingOn = new LinkedHashMap<>();
        for (UUID id : weave.vertices().keySet()) {
            waitingOn.put(id, 0);
        }
        for (Before link : weave.orders()) {
            UUID from = link.from();
            UUID to = link.to();
            if (!weave.vertices().containsKey(from) || !weave.vertices().containsKey(to)) {
                continue;
            }
            next.computeIfAbsent(from, key -> new ArrayList<>()).add(to);
            waitingOn.merge(to, 1, Integer::sum);
        }
        List<UUID> sources = new ArrayList<>();
        waitingOn.forEach((id, count) -> {
            if (count == 0) {
                sources.add(id);
            }
        });
        sources.sort(Comparator.comparingInt(weave::rankOf));
        Deque<UUID> ready = new ArrayDeque<>(sources);
        Set<UUID> sorted = new LinkedHashSet<>(waitingOn.size());
        while (!ready.isEmpty()) {
            UUID id = ready.removeFirst();
            sorted.add(id);
            for (UUID onward : next.getOrDefault(id, List.of())) {
                if (waitingOn.merge(onward, -1, Integer::sum) == 0) {
                    ready.addFirst(onward);
                }
            }
        }
        sorted.addAll(weave.vertices().keySet());
        return new ArrayList<>(sorted);
    }
}
