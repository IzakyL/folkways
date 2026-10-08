package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.passage.Hop;
import io.github.izakyl.folkways.core.api.passage.Passage;
import io.github.izakyl.folkways.core.api.terms.Closures;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Feet;
import io.github.izakyl.folkways.core.engine.travel.Hops;
import io.github.izakyl.folkways.core.engine.travel.PathfindingLoad;
import io.github.izakyl.folkways.core.engine.travel.TravelBudget;
import io.github.izakyl.folkways.core.engine.travel.Urgency;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Roaming {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    private static final long SHELVED_TICKS = 1_200L;

    private static final int ALONG_THE_PLATFORM = 1;

    private static final long RAMBLED_TICKS = 2_400L;

    private static final int MOST_RAMBLES = 2;

    private static final int RAMBLE_EVERY = 20;
    private static final int BUSY_RAMBLE_VISITS = 128;

    private static final int MOST_WALKED = 64;

    private static final int MOST_ANSWERING_ROUNDS = 8;

    private static final int MOST_GOALS_TRIED = 2;

    private static final long EXPIRE_EVERY = 20L;

    private static final long WALKING_TTL = 60L;

    private static final long PLANNING_TTL = 1_200L;

    private static final long BACKGROUND_TTL = 600L;

    private static final long EXTENT_WINDOW_TICKS = 6_000L;

    private record Shelf(Ask ask, long until) {
    }

    private final ServerLevel level;

    private final Realm home;

    private final Asked asked = new Asked();

    private final Trodden trodden = new Trodden();

    private final Deque<Trod> walked = new ArrayDeque<>();

    private final Long2LongOpenHashMap rambledUntil = new Long2LongOpenHashMap();

    private final Pending pending = new Pending();

    private final Pending connecting = new Pending();

    private final Map<Ask.Key, Shelf> shelved = new LinkedHashMap<>();

    private final Map<Tract, Waypoints> nets = new LinkedHashMap<>();

    private final Map<Tract, Waypoints.Net> frozen = new LinkedHashMap<>();

    private final Map<Realm, Extent> extents = new LinkedHashMap<>();

    private long receivedRequests;
    private long enqueuedRequests;
    private long answeredRequests;
    private long startedRequests;
    private long discardedRequests;
    private long reviewedRequests;
    private long sweptRequests;
    private long nextRamble;
    private long nextExpiry;

    private boolean settled;

    private Quest quest;

    private WayGraph published = WayGraph.UNBUILT;

    private List<Closures.Closed> closed = List.of();

    private String said = "";

    public Roaming(ServerLevel level) {
        this.level = level;
        this.home = Realm.of(level);
    }

    public Ways ways() {
        return published;
    }

    public boolean advance(List<Roamer> roamers, List<Hop> hops, long now) {
        return advance(roamers, hops, now, Slice.forWindow(), true);
    }

    public Faring answered(Function<Ways, Faring> asking, List<Roamer> roamers, List<Hop> hops, long now) {
        Faring said = asking.apply(published);
        for (int round = 0; said instanceof Faring.Later && round < MOST_ANSWERING_ROUNDS; round++) {
            if (!answer(roamers, hops, now)) {
                break;
            }
            said = asking.apply(published);
        }
        return said instanceof Faring.Later ? Faring.NO : said;
    }

    private boolean answer(List<Roamer> roamers, List<Hop> hops, long now) {
        Set<ResourceLocation> knew = published.kinds();
        boolean asking = advance(roamers, hops, now, Slice.UNTIL_ANSWERED, false);
        return asking || !published.kinds().equals(knew);
    }

    private boolean advance(List<Roamer> roamers, List<Hop> hops, long now, Slice slice,
                            boolean wandering) {
        Map<ResourceLocation, Sort> sorts = sortsOf(roamers);
        List<Crossing> crossings = crossingsOf(hops);
        forget(sorts, crossings);
        close();
        extents.values().forEach(extent -> extent.roll(now));
        wire(sorts, roamers, crossings, now);
        reconsider(sorts, now);
        List<Ask> fresh = new ArrayList<>();
        for (Ask ask : asked.drain()) {
            receivedRequests++;
            Extent extent = extent(ask.realm());
            extent.covers(BlockPos.of(ask.from()));
            for (int at = 0; at < ask.goals().size(); at++) {
                BlockPos goal = BlockPos.of(ask.goals().getLong(at));
                extent.covers(goal);
                if (at == 0) {
                    extent.wants(goal);
                }
            }
            if (receive(ask, now)) {
                fresh.add(ask);
            }
        }
        for (Trod trod : trodden.drain()) {
            if (walked.size() >= MOST_WALKED) {
                walked.pollFirst();
            }
            walked.addLast(trod);
        }
        for (Roamer roamer : roamers) {
            WorldPos feet = Feet.of(roamer.body());
            extent(feet.realm()).covers(feet.cell());
        }
        for (Crossing crossing : crossings) {
            extent(crossing.boarding().realm()).covers(crossing.boarding().cell());
            extent(crossing.landing().realm()).covers(crossing.landing().cell());
        }
        for (Map.Entry<Tract, Waypoints> held : nets.entrySet()) {
            Extent extent = extent(held.getKey().realm());
            held.getValue().room(extent.ground(), extent.wanted());
        }
        if (now >= nextExpiry) {
            expire(now);
            nextExpiry = now + EXPIRE_EVERY;
        }
        PathfindingLoad.asksPending(pending.size() + connecting.size());
        boolean asking = !pending.isEmpty() || !connecting.isEmpty() || quest != null;
        long began = Util.getNanos();
        if (slice == Slice.UNTIL_ANSWERED) {
            for (Ask ask : fresh) {
                if (pending.remove(ask)) {
                    settleNow(ask, sorts, roamers, now);
                }
            }
        } else {
            boolean explored = wandering && now >= nextRamble
                && wander(sorts, roamers, slice, now, true);
            if (explored) {
                nextRamble = now + RAMBLE_EVERY;
            }
            if (!explored || !slice.spent(0)) {
                work(sorts, roamers, now, slice);
            }
            if (wandering && !explored && quest == null && pending.isEmpty() && connecting.isEmpty()) {
                if (wander(sorts, roamers, slice, now, false)) {
                    nextRamble = now + RAMBLE_EVERY;
                }
            }
        }

        publish(sorts, roamers, crossings, now);

        TravelBudget.spent(Util.getNanos() - began);
        return asking;
    }

    private boolean receive(Ask ask, long now) {
        if (shelved.containsKey(ask.key())) {
            return false;
        }
        if (quest != null && quest.ask().key().equals(ask.key())) {
            return false;
        }
        if (connecting.contains(ask)) {
            connecting.offer(ask, now);
            return false;
        }
        if (pending.offer(ask, now)) {
            enqueuedRequests++;
        }
        return true;
    }

    private void work(Map<ResourceLocation, Sort> sorts, List<Roamer> roamers, long now, Slice slice) {
        int spent;
        do {
            spent = 0;
            boolean moved = false;
            Ask next = pending.pollFirst();
            if (next != null) {
                moved = true;
                spent += serve(next, sorts, roamers, now);
            }
            if (quest == null) {
                Ask joining = connecting.pollFirst();
                if (joining != null) {
                    moved = true;
                    quest = begin(joining, sorts, roamers, now);
                }
            }
            if (quest != null) {
                moved = true;
                Quest.State state = quest.step(now);
                spent += quest.spent();
                if (state != Quest.State.GOING) {
                    settle(quest, state, now);
                    quest = null;
                }
            }
            if (!moved) {
                return;
            }
        } while (!slice.spent(spent));
    }

    private void settleNow(Ask ask, Map<ResourceLocation, Sort> sorts, List<Roamer> roamers, long now) {
        serve(ask, sorts, roamers, now);
        if (!connecting.remove(ask)) {
            return;
        }
        Quest searching = begin(ask, sorts, roamers, now);
        if (searching == null) {
            return;
        }
        Quest.State state;
        do {
            state = searching.step(now);
        } while (state == Quest.State.GOING);
        settle(searching, state, now);
    }

    private int serve(Ask ask, Map<ResourceLocation, Sort> sorts, List<Roamer> roamers, long now) {
        Sort sort = sorts.get(ask.kind());
        Roamer roamer = roamerOf(roamers, ask.kind());
        if (sort == null || roamer == null) {
            discardedRequests++;
            return 0;
        }
        Waypoints net = net(sort, ask.realm());
        Extent extent = extent(ask.realm());
        net.room(extent.ground(), extent.wanted());
        if (answered(net, ask, now)) {
            countAnswered();
            return 0;
        }
        reviewedRequests++;
        Set<BlockPos> goals = new LinkedHashSet<>();
        for (long goal : ask.goals()) {
            goals.add(BlockPos.of(goal));
        }
        Attach.Result from = Attach.end(level, net, roamer, ask.realm(), ask.from(), goals,
            nearest(ask.start(), goals), now);
        int spent = from.visited();
        if (from.outcome() == Attach.Outcome.CLOSED) {
            deny(ask, PathfindingLoad.Denial.NEAR_END, now);
            return spent;
        }
        if (answered(net, ask, now)) {
            countAnswered();
            return spent;
        }
        if (!anyGoalOn(net, ask, now)) {
            boolean open = false;
            List<BlockPos> tried = new ArrayList<>(goals);
            tried.sort(Comparator.comparingDouble(goal -> goal.distSqr(ask.start())));
            for (BlockPos goal : tried.subList(0, Math.min(MOST_GOALS_TRIED, tried.size()))) {
                Attach.Result end = Attach.end(level, net, roamer, ask.realm(), goal.asLong(),
                    Set.of(ask.start()), ask.start(), now);
                spent += end.visited();
                if (end.outcome() != Attach.Outcome.CLOSED) {
                    open = true;
                    break;
                }
            }
            if (!open && tried.size() <= MOST_GOALS_TRIED) {
                deny(ask, PathfindingLoad.Denial.FAR_END, now);
                return spent;
            }
        }
        if (answered(net, ask, now)) {
            countAnswered();
            return spent;
        }
        connecting.offer(ask, now);
        return spent;
    }

    private void countAnswered() {
        answeredRequests++;
        sweptRequests++;
        settled = true;
    }

    private void deny(Ask ask, PathfindingLoad.Denial why, long now) {
        PathfindingLoad.questDenied(why, ask.from(), ask.goals().isEmpty() ? 0L : ask.goals().getLong(0),
            ask.goals().size());
        shelve(ask, now);
        settled = true;
    }

    private static boolean anyGoalOn(Waypoints net, Ask ask, long now) {
        for (long goal : ask.goals()) {
            if (net.near(goal, now) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos nearest(BlockPos from, Set<BlockPos> goals) {
        BlockPos best = from;
        double closest = Double.MAX_VALUE;
        for (BlockPos goal : goals) {
            double apart = goal.distSqr(from);
            if (apart < closest) {
                closest = apart;
                best = goal;
            }
        }
        return best;
    }

    private Quest begin(Ask ask, Map<ResourceLocation, Sort> sorts, List<Roamer> roamers, long now) {
        Sort sort = sorts.get(ask.kind());
        Roamer roamer = roamerOf(roamers, ask.kind());
        if (sort == null || roamer == null) {
            discardedRequests++;
            return null;
        }
        Waypoints net = net(sort, ask.realm());
        if (answered(net, ask, now)) {
            countAnswered();
            return null;
        }
        startedRequests++;
        return new Quest(level, net, roamer, ask, now);
    }

    private void expire(long now) {
        List<Ask> gone = new ArrayList<>(pending.expire(now, Roaming::lasting));
        gone.addAll(connecting.expire(now, Roaming::lasting));
        for (Ask ask : gone) {
            discardedRequests++;
            if (ask.urgency().band() != Urgency.Band.WALKING) {
                settled = true;
            }
        }
    }

    private static long lasting(Ask ask) {
        return switch (ask.urgency().band()) {
            case WALKING -> WALKING_TTL;
            case PLANNING, CHECKING -> PLANNING_TTL;
            case BACKGROUND -> BACKGROUND_TTL;
        };
    }

    private void shelve(Ask ask, long now) {
        shelved.put(ask.key(), new Shelf(ask, now + SHELVED_TICKS));
    }

    private void reconsider(Map<ResourceLocation, Sort> sorts, long now) {
        shelved.values().removeIf(shelf -> {
            if (shelf.until() > now) {
                return false;
            }
            Waypoints net = nets.get(new Tract(sorts.get(shelf.ask().kind()), shelf.ask().realm()));
            if (net != null) {
                net.reconsider(shelf.ask());
            }
            settled = true;
            return true;
        });
    }

    private boolean wander(Map<ResourceLocation, Sort> sorts, List<Roamer> roamers, Slice slice,
                           long now, boolean reserved) {
        if (walked.isEmpty() || (!reserved && slice.spent(0))) {
            return false;
        }
        boolean ran = false;
        rambledUntil.long2LongEntrySet().removeIf(entry -> entry.getLongValue() <= now);
        for (int taken = 0; taken < (reserved ? MOST_WALKED : MOST_RAMBLES); taken++) {
            Trod trod = walked.pollFirst();
            if (trod == null) {
                return ran;
            }
            Sort sort = sorts.get(trod.kind());
            Roamer roamer = roamerOf(roamers, trod.kind());
            Waypoints net = sort == null ? null : nets.get(new Tract(sort, trod.realm()));
            if (sort == null || roamer == null || net == null) {
                continue;
            }
            OptionalLong where = untrodden(trod);
            if (where.isEmpty()) {
                continue;
            }
            rambledUntil.put(where.getAsLong(), now + RAMBLED_TICKS);
            Ramble ramble = new Ramble(level, trod.realm(), net, roamer, where.getAsLong());
            PathfindingLoad.rambled(ramble.run(now, reserved ? BUSY_RAMBLE_VISITS : Sweep.MOST_VISITED));
            ran = true;
            boolean exhausted = slice.spent(ramble.spent());
            if (reserved || exhausted) {
                return true;
            }
        }
        return ran;
    }

    private OptionalLong untrodden(Trod trod) {
        for (int at = 0; at < trod.along().size(); at++) {
            long cell = trod.along().getLong(at);
            if (!rambledUntil.containsKey(cell)) {
                return OptionalLong.of(cell);
            }
        }
        return OptionalLong.empty();
    }

    public boolean answered() {
        boolean said = settled;
        settled = false;
        return said;
    }

    public void wrongEdge(WorldPos from, WorldPos to) {
        if (!from.sameRealm(to)) {
            return;
        }
        long now = level.getGameTime();
        boolean struck = false;
        for (Map.Entry<Tract, Waypoints> held : nets.entrySet()) {
            if (held.getKey().realm().equals(from.realm())) {
                struck |= held.getValue().wrongEdge(from.cell().asLong(), to.cell().asLong(), now);
            }
        }
        if (struck) {
            PathfindingLoad.edgeStruck();
        }
    }

    public void walkedEdge(WorldPos from, WorldPos to, int ticks) {
        if (!from.sameRealm(to)) {
            return;
        }
        long now = level.getGameTime();
        for (Map.Entry<Tract, Waypoints> held : nets.entrySet()) {
            if (held.getKey().realm().equals(from.realm())) {
                held.getValue().walked(from.cell().asLong(), to.cell().asLong(), ticks, now);
            }
        }
        PathfindingLoad.edgeWalked();
    }

    private static List<Crossing> crossingsOf(List<Hop> hops) {
        List<Crossing> laid = new ArrayList<>(hops.size());
        for (Hop hop : hops) {
            Optional<Passage> ran = Hops.laid(hop);
            Optional<WorldPos> boarding = hubOf(hop.boarding());
            Optional<WorldPos> landing = hubOf(hop.landing());
            if (ran.isEmpty() || boarding.isEmpty() || landing.isEmpty()) {
                continue;
            }
            laid.add(new Crossing(boarding.get(), landing.get(), hop, ran.get().id()));
        }
        return List.copyOf(laid);
    }

    private static Optional<WorldPos> hubOf(Stances.Cells where) {
        Map<Realm, List<WorldPos>> byRealm = new LinkedHashMap<>();
        for (WorldPos cell : where.cells()) {
            byRealm.computeIfAbsent(cell.realm(), realm -> new ArrayList<>()).add(cell);
        }
        List<WorldPos> most = List.of();
        for (List<WorldPos> one : byRealm.values()) {
            if (one.size() > most.size() || (one.size() == most.size() && before(one, most))) {
                most = one;
            }
        }
        if (most.isEmpty()) {
            return Optional.empty();
        }
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (WorldPos cell : most) {
            x += cell.cell().getX();
            y += cell.cell().getY();
            z += cell.cell().getZ();
        }
        BlockPos middle = BlockPos.containing(x / most.size(), y / most.size(), z / most.size());
        WorldPos anchor = null;
        for (WorldPos cell : most) {
            if (anchor == null || nearer(cell.cell(), anchor.cell(), middle)) {
                anchor = cell;
            }
        }
        return Optional.of(anchor);
    }

    private static boolean before(List<WorldPos> one, List<WorldPos> than) {
        return least(one) < least(than);
    }

    private static long least(List<WorldPos> cells) {
        long least = Long.MAX_VALUE;
        for (WorldPos cell : cells) {
            least = Math.min(least, cell.cell().asLong());
        }
        return least;
    }

    private static boolean nearer(BlockPos cell, BlockPos than, BlockPos middle) {
        double apart = cell.distSqr(middle);
        double other = than.distSqr(middle);
        return apart == other ? cell.asLong() < than.asLong() : apart < other;
    }

    private void wire(Map<ResourceLocation, Sort> sorts, List<Roamer> roamers,
                      List<Crossing> crossings, long now) {
        PathfindingLoad.hopsWired(crossings.size());
        if (crossings.isEmpty()) {
            return;
        }
        for (Sort sort : sorts.values()) {
            for (Crossing crossing : crossings) {
                platform(net(sort, crossing.boarding().realm()), crossing.hop().boarding(),
                    crossing.boarding(), now);
                platform(net(sort, crossing.landing().realm()), crossing.hop().landing(),
                    crossing.landing(), now);
            }
        }
        askTheWayTo(roamers, sorts, crossings);
    }

    private static void platform(Waypoints net, Stances.Cells footings, WorldPos anchor, long now) {
        int hub = net.pin(anchor.cell().asLong(), now);
        for (WorldPos cell : footings.cells()) {
            if (!cell.sameRealm(anchor)) {

                continue;
            }
            int id = net.pin(cell.cell().asLong(), now);
            net.link(hub, id, ALONG_THE_PLATFORM, now);
            net.link(id, hub, ALONG_THE_PLATFORM, now);
        }
    }

    private void askTheWayTo(List<Roamer> roamers, Map<ResourceLocation, Sort> sorts,
                             List<Crossing> crossings) {
        for (Roamer roamer : roamers) {
            if (sorts.get(roamer.kind()) == null || roamer.body().isRemoved()) {
                continue;
            }
            WorldPos from = Feet.of(roamer.body());
            for (Crossing crossing : crossings) {
                asked.note(roamer.kind(), from, crossing.hop().boarding().cells());
                asked.note(roamer.kind(), from, crossing.hop().landing().cells());
            }
        }
    }

    private Waypoints net(Sort sort, Realm realm) {
        return nets.computeIfAbsent(new Tract(sort, realm), tract -> {
            Waypoints fresh = new Waypoints();
            if (realm.equals(home)) {
                closed.forEach(one -> fresh.closeOff(one.box()));
            }
            return fresh;
        });
    }

    // Ground closed since the last tick has its ways taken up; ground opened again may be come through once more,
    // so what was put by for want of a way is asked again.
    private void close() {
        List<Closures.Closed> now = Closures.in(level);
        if (now == closed) {
            return;
        }
        for (Closures.Closed one : now) {
            if (absent(closed, one)) {
                nets.forEach((tract, net) -> {
                    if (tract.realm().equals(home)) {
                        net.closeOff(one.box());
                    }
                });
            }
        }
        for (Closures.Closed one : closed) {
            if (absent(now, one)) {
                nets.forEach((tract, net) -> {
                    if (tract.realm().equals(home)) {
                        net.reopen(one.box());
                    }
                });
                shelved.clear();
            }
        }
        closed = now;
        settled = true;
    }

    private static boolean absent(List<Closures.Closed> among, Closures.Closed one) {
        for (Closures.Closed each : among) {
            if (each == one) {
                return false;
            }
        }
        return true;
    }

    private static boolean answered(Waypoints net, Ask ask, long now) {
        int from = net.near(ask.from(), now);
        IntArrayList ends = new IntArrayList(ask.goals().size());
        for (int at = 0; at < ask.goals().size(); at++) {
            ends.add(net.near(ask.goals().getLong(at), now));
        }
        return net.joinsAny(from, ends, now);
    }

    private Extent extent(Realm realm) {
        return extents.computeIfAbsent(realm, where -> new Extent());
    }

    private void settle(Quest done, Quest.State state, long now) {
        settled = true;
        switch (state) {
            case FOUND -> PathfindingLoad.questFound();

            case DENIED -> {
                PathfindingLoad.questDenied(done.denial(), done.ask().from(),
                    done.ask().goals().isEmpty() ? 0L : done.ask().goals().getLong(0),
                    done.ask().goals().size());
                shelve(done.ask(), now);
            }
            case GAVE_UP -> {
                PathfindingLoad.questGaveUp();
                shelve(done.ask(), now);
            }
            case GOING -> {
            }
        }
    }

    private void forget(Map<ResourceLocation, Sort> sorts, List<Crossing> crossings) {
        Set<Sort> alive = new LinkedHashSet<>(sorts.values());
        Set<Realm> standing = new LinkedHashSet<>();
        standing.add(home);
        for (Tract tract : nets.keySet()) {
            keepFrame(standing, tract.realm());
        }
        for (Realm realm : extents.keySet()) {
            keepFrame(standing, realm);
        }
        for (Crossing crossing : crossings) {
            standing.add(crossing.boarding().realm());
            standing.add(crossing.landing().realm());
        }
        Predicate<Tract> gone = tract -> !alive.contains(tract.sort())
            || !standing.contains(tract.realm());
        nets.keySet().removeIf(gone);
        frozen.keySet().removeIf(gone);
        extents.keySet().removeIf(realm -> !standing.contains(realm));
        Predicate<Ask> lost = ask -> {
            if (sorts.containsKey(ask.kind()) && standing.contains(ask.realm())) {
                return false;
            }
            discardedRequests++;
            return true;
        };
        pending.removeIf(lost);
        connecting.removeIf(lost);
        shelved.values().removeIf(shelf -> !sorts.containsKey(shelf.ask().kind())
            || !standing.contains(shelf.ask().realm()));
        if (quest != null && (!sorts.containsKey(quest.ask().kind())
            || !standing.contains(quest.ask().realm()))) {
            quest = null;
        }
    }

    private void keepFrame(Set<Realm> standing, Realm realm) {
        if (realm instanceof Realm.Frame frame
                && WorldSpaces.frame(level, frame.structure()).isPresent()) {
            standing.add(realm);
        }
    }

    private void publish(Map<ResourceLocation, Sort> sorts, List<Roamer> roamers,
                         List<Crossing> crossings, long now) {
        int points = 0;
        int links = 0;
        long straightening = 0;
        long freezing = 0;
        for (Map.Entry<Tract, Waypoints> held : nets.entrySet()) {
            Waypoints net = held.getValue();
            if (net.trim(now)) {
                PathfindingLoad.renumbered();
            }
            if (quest == null && net.changed(now)) {
                long began = Util.getNanos();
                PathfindingLoad.straightened(net.straighten(now));
                straightening += Util.getNanos() - began;
            }
            if (net.changed(now) || !frozen.containsKey(held.getKey())) {
                long began = Util.getNanos();
                frozen.put(held.getKey(), net.freeze(now));
                freezing += Util.getNanos() - began;
            }
            points += net.size();
            links += net.links();
        }
        PathfindingLoad.published(straightening, freezing);
        int shut = 0;
        for (Crossing crossing : crossings) {
            shut += crossing.hop().fare().arriveBy(now).isEmpty() ? 1 : 0;
        }
        int pieces = 0;
        for (Map.Entry<Tract, Waypoints.Net> held : frozen.entrySet()) {
            Waypoints.Net net = held.getValue();
            int apart = net.apart();
            pieces += apart;
            if (apart > 1) {
                say("ways in pieces " + held.getKey().realm() + " " + net.spellPieces(4));
            }
        }
        PathfindingLoad.graphHolds(this, points, links, shut, pieces);
        published = new WayGraph(sorts, Map.copyOf(frozen), crossings, feetOf(roamers, sorts),
            asked, trodden, closed, now);
    }

    private void say(String line) {
        if (line.equals(said)) {
            return;
        }
        said = line;
        LOGGER.info("{}", line);
    }

    private static Roamer roamerOf(List<Roamer> roamers, ResourceLocation kind) {
        for (Roamer roamer : roamers) {
            if (roamer.kind().equals(kind) && !roamer.body().isRemoved()) {
                return roamer;
            }
        }
        return null;
    }

    private static Map<ResourceLocation, Sort> sortsOf(List<Roamer> roamers) {
        Map<ResourceLocation, Sort> sorts = new LinkedHashMap<>();
        for (Roamer roamer : roamers) {
            sorts.putIfAbsent(roamer.kind(),
                new Sort(roamer.locomotion().id(), Bulk.of(roamer.body())));
        }
        return sorts;
    }

    private Map<Sort, List<WorldPos>> feetOf(List<Roamer> roamers,
                                             Map<ResourceLocation, Sort> sorts) {
        Map<Sort, List<WorldPos>> feet = new LinkedHashMap<>();
        for (Roamer roamer : roamers) {
            Sort sort = sorts.get(roamer.kind());
            if (sort == null || roamer.body().isRemoved() || roamer.body().level() != level) {
                continue;
            }
            feet.computeIfAbsent(sort, key -> new ArrayList<>())
                .add(Feet.of(roamer.body()));
        }
        feet.replaceAll((sort, cells) -> List.copyOf(cells));
        return feet;
    }

    private static final class Extent {

        private LongOpenHashSet wanted = new LongOpenHashSet();

        private int westmost = Integer.MAX_VALUE;
        private int eastmost = Integer.MIN_VALUE;
        private int northmost = Integer.MAX_VALUE;
        private int southmost = Integer.MIN_VALUE;

        private int wantedBefore;
        private int westBefore = Integer.MAX_VALUE;
        private int eastBefore = Integer.MIN_VALUE;
        private int northBefore = Integer.MAX_VALUE;
        private int southBefore = Integer.MIN_VALUE;

        private long windowFrom = -1L;

        void roll(long now) {
            if (windowFrom < 0L) {
                windowFrom = now;
                return;
            }
            if (now - windowFrom < EXTENT_WINDOW_TICKS) {
                return;
            }
            windowFrom = now;
            wantedBefore = wanted.size();
            westBefore = westmost;
            eastBefore = eastmost;
            northBefore = northmost;
            southBefore = southmost;
            wanted = new LongOpenHashSet();
            westmost = Integer.MAX_VALUE;
            eastmost = Integer.MIN_VALUE;
            northmost = Integer.MAX_VALUE;
            southmost = Integer.MIN_VALUE;
        }

        void covers(BlockPos at) {
            westmost = Math.min(westmost, at.getX());
            eastmost = Math.max(eastmost, at.getX());
            northmost = Math.min(northmost, at.getZ());
            southmost = Math.max(southmost, at.getZ());
        }

        void wants(BlockPos at) {
            wanted.add(at.asLong());
        }

        int wanted() {
            return Math.max(wanted.size(), wantedBefore);
        }

        long ground() {
            int west = Math.min(westmost, westBefore);
            int east = Math.max(eastmost, eastBefore);
            int north = Math.min(northmost, northBefore);
            int south = Math.max(southmost, southBefore);
            if (east < west) {
                return 0L;
            }
            return (long) (east - west + 1) * (south - north + 1);
        }
    }
}
