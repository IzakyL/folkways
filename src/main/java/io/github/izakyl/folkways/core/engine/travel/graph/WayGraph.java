package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.passage.Hop;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Closures;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Journey;
import io.github.izakyl.folkways.core.engine.travel.Leg;
import io.github.izakyl.folkways.core.engine.travel.Urgency;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

public final class WayGraph implements Ways {

    public static final WayGraph UNBUILT =
        new WayGraph(Map.of(), Map.of(), List.of(), Map.of(), new Asked(), new Trodden(), List.of(), 0L);

    private static final int MOST_DOORS_TRIED = 8;

    private final Map<ResourceLocation, Sort> sorts;
    private final Map<Tract, Waypoints.Net> nets;
    private final List<Crossing> crossings;
    private final Map<Sort, List<WorldPos>> feet;
    private final Asked asked;
    private final Trodden trodden;

    private final List<Closures.Closed> closed;

    private final List<List<WorldPos>> doors;

    private final long at;

    private final Map<Sort, Web> webs = new ConcurrentHashMap<>();

    WayGraph(Map<ResourceLocation, Sort> sorts, Map<Tract, Waypoints.Net> nets,
             List<Crossing> crossings, Map<Sort, List<WorldPos>> feet, Asked asked,
             Trodden trodden, List<Closures.Closed> closed, long at) {
        this.sorts = Map.copyOf(sorts);
        this.nets = Map.copyOf(nets);
        this.crossings = List.copyOf(crossings);
        this.feet = Map.copyOf(feet);
        this.asked = asked;
        this.trodden = trodden;
        this.closed = List.copyOf(closed);
        this.doors = doorsOf(this.closed);
        this.at = at;
    }

    Set<ResourceLocation> kinds() {
        return sorts.keySet();
    }

    private record Asker(ResourceLocation kind, UUID requester, Urgency urgency, boolean noting) {
    }

    @Override
    public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
        return journey(who, from, goals, Urgency.PLANNING);
    }

    @Override
    public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals, Urgency urgency) {
        Sort sort = sorts.get(who.kind());

        return sort == null ? Faring.LATER
            : fare(new Asker(who.kind(), who.id(), urgency, true), sort, from, goals);
    }

    @Override
    public Faring anyoneReaches(Set<WorldPos> goals) {
        if (sorts.isEmpty()) {
            return Faring.LATER;
        }
        Faring said = Faring.NO;
        for (Map.Entry<ResourceLocation, Sort> kind : sorts.entrySet()) {
            Faring answer = fromAnyFoot(kind.getKey(), kind.getValue(), goals);
            switch (answer) {
                case Faring.Yes ignored -> {
                    return answer;
                }
                case Faring.Later ignored -> said = Faring.LATER;
                case Faring.No ignored -> {
                }
            }
        }
        return said;
    }

    private Faring fromAnyFoot(ResourceLocation kind, Sort sort, Set<WorldPos> goals) {
        List<WorldPos> standing = feet.getOrDefault(sort, List.of());
        if (standing.isEmpty()) {
            return Faring.LATER;
        }
        Faring said = Faring.NO;
        WorldPos waiting = null;
        Asker quiet = new Asker(kind, null, Urgency.CHECKING, false);
        for (WorldPos foot : standing) {
            Faring answer = fare(quiet, sort, foot, goals);
            switch (answer) {
                case Faring.Yes ignored -> {
                    return answer;
                }
                case Faring.Later ignored -> {
                    said = Faring.LATER;
                    if (waiting == null) {
                        waiting = foot;
                    }
                }
                case Faring.No ignored -> {
                }
            }
        }
        if (waiting != null) {
            fare(new Asker(kind, null, Urgency.CHECKING, true), sort, waiting, goals);
        }
        return said;
    }

    /**
     * Ground closed over is not on the ways: a goal in it is come to by way of its doors and walked to from there,
     * and a body in it walks out by the door nearest first. Two places in the same closed ground are walked between.
     */
    private Faring fare(Asker asker, Sort sort, WorldPos from, Set<WorldPos> goals) {
        if (closed.isEmpty() || goals.isEmpty()) {
            return direct(asker, sort, from, goals, Map.of());
        }
        int leaving = closedAt(closed, from);
        Map<Integer, Set<WorldPos>> within = new LinkedHashMap<>();
        Set<WorldPos> open = new LinkedHashSet<>();
        for (WorldPos goal : goals) {
            int in = closedAt(closed, goal);
            if (in < 0) {
                open.add(goal);
            } else {
                within.computeIfAbsent(in, key -> new LinkedHashSet<>()).add(goal);
            }
        }
        if (leaving >= 0 && within.containsKey(leaving)) {
            Set<WorldPos> here = within.get(leaving);
            return Faring.yes(Journey.afoot(walk(from, here), here));
        }
        Map<WorldPos, Set<WorldPos>> beyond = new LinkedHashMap<>();
        within.forEach((in, inside) -> {
            for (WorldPos door : doorsNear(in, inside)) {
                beyond.computeIfAbsent(door, key -> new LinkedHashSet<>()).addAll(inside);
                open.add(door);
            }
        });
        if (leaving < 0) {
            return direct(asker, sort, from, open, beyond);
        }
        // Out by the nearest door that has a way on; failing all, the way on is asked from the nearest.
        List<WorldPos> out = doorsNear(leaving, Set.of(from));
        if (out.isEmpty()) {
            return Faring.NO;
        }
        Asker quiet = new Asker(asker.kind(), asker.requester(), asker.urgency(), false);
        Faring said = Faring.NO;
        for (WorldPos door : out) {
            Faring onward = direct(quiet, sort, door, open, beyond);
            if (onward instanceof Faring.Yes(Journey by)) {
                return Faring.yes(outBy(from, door, by));
            }
            if (onward instanceof Faring.Later) {
                said = Faring.LATER;
            }
        }
        if (said instanceof Faring.Later && asker.noting()) {
            direct(asker, sort, out.getFirst(), open, beyond);
        }
        return said;
    }

    private static Journey outBy(WorldPos from, WorldPos door, Journey by) {
        List<Leg> legs = new ArrayList<>(by.legs().size() + 1);
        legs.add(Leg.afoot(Set.of(door)));
        legs.addAll(by.legs());
        return new Journey(by.walkTicks() + walk(from, Set.of(door)), by.rideTicks(), legs);
    }

    // Which of the closures holds the cell, or -1.
    private static int closedAt(List<Closures.Closed> closed, WorldPos cell) {
        for (int at = 0; at < closed.size(); at++) {
            if (closed.get(at).holds(cell)) {
                return at;
            }
        }
        return -1;
    }

    // Each closure's doors that stand in no other closure, worked out once for the graph.
    private static List<List<WorldPos>> doorsOf(List<Closures.Closed> closed) {
        List<List<WorldPos>> doors = new ArrayList<>(closed.size());
        for (Closures.Closed one : closed) {
            List<WorldPos> open = new ArrayList<>(one.doors().size());
            for (WorldPos door : one.doors()) {
                if (closedAt(closed, door) < 0) {
                    open.add(door);
                }
            }
            doors.add(List.copyOf(open));
        }
        return List.copyOf(doors);
    }

    // The doors of the closure nearest any of the places, the nearest first.
    private List<WorldPos> doorsNear(int closure, Set<WorldPos> places) {
        List<WorldPos> doors = this.doors.get(closure);
        int most = Math.min(MOST_DOORS_TRIED, doors.size());
        WorldPos[] best = new WorldPos[most];
        double[] apart = new double[most];
        int held = 0;
        for (WorldPos door : doors) {
            double far = Double.MAX_VALUE;
            for (WorldPos place : places) {
                far = Math.min(far, door.cell().distSqr(place.cell()));
            }
            if (held == most && far >= apart[most - 1]) {
                continue;
            }
            int at = held == most ? most - 1 : held++;
            while (at > 0 && apart[at - 1] > far) {
                best[at] = best[at - 1];
                apart[at] = apart[at - 1];
                at--;
            }
            best[at] = door;
            apart[at] = far;
        }
        return List.of(best).subList(0, held);
    }

    private static int walk(WorldPos from, Set<WorldPos> to) {
        int best = Integer.MAX_VALUE;
        for (WorldPos one : to) {
            best = Math.min(best, Waypoints.Net.ticks(from.cell(), one.cell()));
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    // A journey that lands on a door goes on afoot into the closed ground behind it.
    private static Faring through(Journey by, WorldPos landed, Map<WorldPos, Set<WorldPos>> beyond) {
        Set<WorldPos> inside = beyond.get(landed);
        if (inside == null) {
            return Faring.yes(by);
        }
        List<Leg> legs = new ArrayList<>(by.legs());
        Leg last = legs.isEmpty() ? null : legs.removeLast();
        legs.add(last == null || last.from().isEmpty() ? Leg.afoot(Set.of(landed))
            : Leg.afoot(last.from().get(), Set.of(landed)));
        legs.add(Leg.afoot(inside));
        return Faring.yes(new Journey(by.walkTicks() + walk(landed, inside), by.rideTicks(), legs));
    }

    private Faring direct(Asker asker, Sort sort, WorldPos from, Set<WorldPos> goals,
                          Map<WorldPos, Set<WorldPos>> beyond) {
        ResourceLocation kind = asker.kind();
        if (goals.isEmpty()) {

            return Faring.NO;
        }
        Web web = webs.computeIfAbsent(sort, this::weave);
        List<WorldPos> live = new ArrayList<>(goals.size());
        for (WorldPos goal : goals) {
            if (goal.equals(from)) {
                return through(Journey.afoot(0, Set.of(goal)), goal, beyond);
            }
            if (!web.shutBetween(from, goal)) {
                live.add(goal);
            }
        }
        if (live.isEmpty()) {
            return Faring.NO;
        }
        int start = web.at(from);
        if (start < 0) {
            return later(asker, from, live);
        }
        int approachFrom = web.approach(from, start);
        IntArrayList ends = new IntArrayList();
        List<WorldPos> landings = new ArrayList<>();
        for (WorldPos goal : live) {
            int id = web.at(goal);
            if (id == start) {
                return through(new Journey(Waypoints.Net.ticks(from.cell(), goal.cell()), 0,
                    List.of(Leg.afoot(from, Set.of(goal)))), goal, beyond);
            }
            if (id >= 0 && web.joined(start, id)) {
                ends.add(id);
                landings.add(goal);
            }
        }
        if (ends.isEmpty()) {
            return later(asker, from, live);
        }

        Arrival arrived = route(web, kind, start, ends, at + approachFrom);
        if (arrived == null) {
            return later(asker, from, live);
        }
        WorldPos landed = landings.get(arrived.which());
        if (asker.noting()) {
            trodden.note(kind, from.realm(), web.cellsIn(from.realm(), arrived.along()));
        }
        int approach = approachFrom + web.approach(landed, ends.getInt(arrived.which()));
        return through(new Journey(ticksOf(arrived.walked() + approach),
            ticksOf(arrived.ridden()), legs(web, arrived, from, live, landed.realm())), landed, beyond);
    }

    private static List<Leg> legs(Web web, Arrival arrived, WorldPos from, List<WorldPos> live,
                                  Realm ending) {
        int[] path = arrived.path();
        List<Leg> walked = new ArrayList<>(path.length + 1);
        WorldPos previous = from;
        Crossing landed = null;
        for (int at = 0; at < path.length; at++) {
            Crossing via = arrived.via()[at];
            if (via != null) {
                // The hub and the platform links are virtual, so the hub can sit on fenced track: walk to any footing instead.
                Set<WorldPos> footings = via.hop().boarding().cells();
                while (!walked.isEmpty() && walked.getLast().passing()
                        && onPlatform(walked.getLast().to().iterator().next(), footings, via.boarding())) {
                    previous = walked.removeLast().from().orElse(from);
                }
                walked.add(Leg.afoot(previous, footings));
                Hop hop = via.hop();
                walked.add(Leg.aboard(new Hop(hop.service(), hop.boarding(), hop.landing(),
                    hop.fare().boardingAt(arrived.boardAt()[at]))));
                previous = via.landing();
                landed = via;
                continue;
            }
            WorldPos here = web.cellAt(path[at]);
            if (landed != null && onPlatform(here, landed.hop().landing().cells(), landed.landing())) {
                previous = here;
                continue;
            }
            landed = null;
            if (!here.equals(previous)) {
                walked.add(Leg.through(previous, here));
                previous = here;
            }
        }
        Set<WorldPos> last = new LinkedHashSet<>();
        for (WorldPos goal : live) {
            if (goal.realm().equals(ending)) {
                last.add(goal);
            }
        }
        walked.add(Leg.afoot(previous, last));
        return walked;
    }

    private static boolean onPlatform(WorldPos cell, Set<WorldPos> footings, WorldPos hub) {
        return cell.equals(hub) || footings.contains(cell);
    }

    private Faring later(Asker asker, WorldPos asking, Collection<WorldPos> goals) {
        if (!asker.noting()) {
            return Faring.LATER;
        }
        Set<Realm> elsewhere = new LinkedHashSet<>();
        for (WorldPos goal : goals) {
            if (!goal.sameRealm(asking)) {
                elsewhere.add(goal.realm());
            }
        }
        asked.note(asker.kind(), asker.requester(), asking, goals, asker.urgency());
        if (elsewhere.isEmpty()) {
            return Faring.LATER;
        }
        for (Crossing crossing : crossings) {
            if (crossing.boarding().sameRealm(asking)) {
                asked.note(asker.kind(), asker.requester(), asking, Set.of(crossing.boarding()),
                    asker.urgency());
            }
            if (elsewhere.contains(crossing.landing().realm())) {
                asked.note(asker.kind(), asker.requester(), crossing.landing(), goals, asker.urgency());
            }
        }
        return Faring.LATER;
    }

    // boardAt[k] is when the walk reaches the crossing taken at path[k]: the run of it the plan has caught.
    private record Arrival(int which, int[] path, Crossing[] via, long[] boardAt, IntArrayList along, long walked,
                           long ridden) {
    }

    private static Arrival route(Web web, ResourceLocation kind, int start, IntArrayList goals,
                                 long began) {
        int count = web.size();
        long[] best = new long[count];
        int[] cameFrom = new int[count];
        Crossing[] cameOn = new Crossing[count];
        Arrays.fill(best, Long.MAX_VALUE);
        Arrays.fill(cameFrom, -1);
        best[start] = began;
        PriorityQueue<long[]> open = new PriorityQueue<>(Comparator.comparingLong(step -> step[1]));
        open.add(new long[] {start, began});
        int arrived = -1;
        while (!open.isEmpty()) {
            long[] step = open.poll();
            int here = (int) step[0];
            if (step[1] > best[here]) {
                continue;
            }
            int which = goals.indexOf(here);
            if (which >= 0) {
                arrived = which;
                break;
            }
            int tract = web.tractOf(here);
            Waypoints.Net net = web.netAt(tract);
            int local = here - web.offsetAt(tract);
            for (int edge = net.head()[local]; edge < net.head()[local + 1]; edge++) {
                int next = web.offsetAt(tract) + net.to()[edge];
                long arrives = best[here] + net.cost()[edge];
                if (arrives < best[next]) {
                    best[next] = arrives;
                    cameFrom[next] = here;
                    cameOn[next] = null;
                    open.add(new long[] {next, arrives});
                }
            }
            for (int edge = web.hopHead()[here]; edge < web.hopHead()[here + 1]; edge++) {
                Crossing crossing = web.hopBy()[edge];
                if (Participations.between(kind, Stake.passage(crossing.passage()))
                    == Participation.NONE) {
                    continue;
                }
                OptionalLong arrives = crossing.hop().fare().arriveBy(best[here]);
                if (arrives.isEmpty()) {

                    continue;
                }
                int next = web.hopTo()[edge];
                if (arrives.getAsLong() < best[next]) {
                    best[next] = arrives.getAsLong();
                    cameFrom[next] = here;
                    cameOn[next] = crossing;
                    open.add(new long[] {next, arrives.getAsLong()});
                }
            }
        }
        if (arrived < 0) {
            return null;
        }
        int goal = goals.getInt(arrived);
        IntArrayList along = new IntArrayList();
        List<Crossing> on = new ArrayList<>();
        LongArrayList reached = new LongArrayList();
        long riding = 0L;
        for (int at = goal; ; at = cameFrom[at]) {
            along.add(at);
            on.add(cameOn[at]);
            reached.add(cameOn[at] == null ? -1L : best[cameFrom[at]]);
            if (cameFrom[at] < 0) {
                break;
            }
            if (cameOn[at] != null) {

                riding += best[at] - best[cameFrom[at]];
            }
        }
        int[] path = new int[along.size()];
        Crossing[] via = new Crossing[along.size()];
        long[] boardAt = new long[along.size()];
        for (int at = 0; at < path.length; at++) {
            path[at] = along.getInt(path.length - 1 - at);
            via[at] = on.get(path.length - 1 - at);
            boardAt[at] = reached.getLong(path.length - 1 - at);
        }
        long whole = best[goal] - began;
        return new Arrival(arrived, path, via, boardAt, along, whole - riding, riding);
    }

    private static int ticksOf(long ticks) {
        return (int) Math.clamp(ticks, 0L, Integer.MAX_VALUE);
    }

    private Web weave(Sort sort) {
        List<Realm> realms = new ArrayList<>();
        List<Waypoints.Net> held = new ArrayList<>();
        for (Map.Entry<Tract, Waypoints.Net> one : nets.entrySet()) {
            if (one.getKey().sort().equals(sort) && one.getValue().cells().length > 0) {
                realms.add(one.getKey().realm());
                held.add(one.getValue());
            }
        }
        return new Web(realms, held, crossings);
    }

    private static final class Web {

        private final Map<Realm, Integer> tracts = new LinkedHashMap<>();
        private final List<Realm> realms;
        private final Waypoints.Net[] nets;
        private final int[] offset;
        private final int[] part;
        private final int[] hopHead;
        private final int[] hopTo;
        private final Crossing[] hopBy;

        Web(List<Realm> realms, List<Waypoints.Net> held, List<Crossing> crossings) {
            this.nets = held.toArray(new Waypoints.Net[0]);
            this.realms = List.copyOf(realms);
            this.offset = new int[nets.length + 1];
            for (int at = 0; at < realms.size(); at++) {
                tracts.put(realms.get(at), at);
                offset[at + 1] = offset[at] + nets[at].cells().length;
            }
            int count = offset[nets.length];
            this.part = new int[count];
            for (int tract = 0; tract < nets.length; tract++) {
                int[] apart = nets[tract].part();
                for (int local = 0; local < apart.length; local++) {
                    part[offset[tract] + local] = offset[tract] + apart[local];
                }
            }
            int[] from = new int[crossings.size()];
            int[] to = new int[crossings.size()];
            Crossing[] by = new Crossing[crossings.size()];
            int laid = 0;
            for (Crossing crossing : crossings) {
                int boarding = at(crossing.boarding());
                int landing = at(crossing.landing());
                if (boarding < 0 || landing < 0) {

                    continue;
                }
                from[laid] = boarding;
                to[laid] = landing;
                by[laid] = crossing;
                laid++;
                Waypoints.join(part, boarding, landing);
            }
            this.hopHead = new int[count + 1];
            for (int edge = 0; edge < laid; edge++) {
                hopHead[from[edge] + 1]++;
            }
            for (int vertex = 0; vertex < count; vertex++) {
                hopHead[vertex + 1] += hopHead[vertex];
            }
            int[] cursor = hopHead.clone();
            this.hopTo = new int[laid];
            this.hopBy = new Crossing[laid];
            for (int edge = 0; edge < laid; edge++) {
                int at = cursor[from[edge]]++;
                hopTo[at] = to[edge];
                hopBy[at] = by[edge];
            }
            for (int vertex = 0; vertex < count; vertex++) {
                part[vertex] = Waypoints.root(part, vertex);
            }
        }

        int size() {
            return offset[nets.length];
        }

        int[] hopHead() {
            return hopHead;
        }

        int[] hopTo() {
            return hopTo;
        }

        Crossing[] hopBy() {
            return hopBy;
        }

        int at(WorldPos where) {
            Integer tract = tracts.get(where.realm());
            if (tract == null) {
                return -1;
            }
            int local = nets[tract].near(where.cell().asLong());
            return local < 0 ? -1 : offset[tract] + local;
        }

        WorldPos cellAt(int vertex) {
            int tract = tractOf(vertex);
            return new WorldPos(realms.get(tract), BlockPos.of(nets[tract].cells()[vertex - offset[tract]]));
        }

        LongArrayList cellsIn(Realm realm, IntArrayList along) {
            Integer tract = tracts.get(realm);
            if (tract == null) {
                return LongArrayList.of();
            }
            int from = offset[tract];
            int until = offset[tract + 1];
            LongArrayList cells = new LongArrayList(along.size());
            for (int at = 0; at < along.size(); at++) {
                int vertex = along.getInt(at);
                if (vertex >= from && vertex < until) {
                    cells.add(nets[tract].cells()[vertex - from]);
                }
            }
            return cells;
        }

        int tractOf(int vertex) {
            int found = Arrays.binarySearch(offset, vertex);
            return found >= 0 ? found : -found - 2;
        }

        Waypoints.Net netAt(int tract) {
            return nets[tract];
        }

        int offsetAt(int tract) {
            return offset[tract];
        }

        boolean joined(int from, int to) {
            return from >= 0 && to >= 0 && part[from] == part[to];
        }

        int approach(WorldPos where, int vertex) {
            int tract = tractOf(vertex);
            return nets[tract].approach(where.cell(), vertex - offset[tract]);
        }

        BlockPos pieceCell(int vertex) {
            int tract = tractOf(vertex);
            return nets[tract].pieceCell(vertex - offset[tract]);
        }

        boolean shutBetween(WorldPos from, WorldPos to) {
            Integer tract = tracts.get(from.realm());
            return tract != null && from.sameRealm(to)
                && nets[tract].shutBetween(from.cell(), to.cell());
        }
    }
}
