package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.passage.Hop;
import io.github.izakyl.folkways.core.api.passage.Passage;
import io.github.izakyl.folkways.core.api.terms.Closures;
import io.github.izakyl.folkways.core.api.terms.Reach;
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
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
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

    private static final long RAMBLED_TICKS = 2_400L;

    private static final int MOST_RAMBLES = 2;

    private static final int RAMBLE_EVERY = 20;
    private static final int BUSY_RAMBLE_VISITS = 128;

    private static final int MOST_WALKED = 64;

    // Loose pieces of the ways (a platform's footings, ground walked to once, half a town) are rambled toward the
    // nearest of the rest this often, a few at a time, so the colony's ways grow into one rather than staying in islands.
    private static final int BRIDGE_EVERY = 20;

    private static final int MOST_BRIDGES = 2;

    // A gap rambled across and still open rests RAMBLED_TICKS before it is tried again, twice as long each time up
    // to sixteen times: islands no ramble reaches (a fenced pen, a platform out on its own) stop costing.
    private static final int MOST_BRIDGE_BACKOFFS = 4;

    // A loose piece just rambled from, or found with no gap to try, is not read again for this long: the ways grown
    // meanwhile may have brought another gap.
    private static final long PIECE_RESTS = 200L;

    // Pinned points with no link (a platform's footings its sweep did not come to) are each swept round from, a few
    // at a time and no farther than their neighbours: every footing the sweep comes to is joined in passing.
    private static final int MOST_FOOTINGS = 4;

    private static final int FOOTING_VISITS = 64;

    // The work sites new to the colony whose footings are reckoned in one advance, each a reach and sight test round
    // the block: a colony loaded with hundreds of them joins them over some ticks rather than in one.
    private static final int MOST_SITES_RECKONED = 16;

    // While the colony runs on a budget, the ways are straightened and frozen for the planner at most this often;
    // what changed in between is seen at the next freeze.
    private static final long FREEZE_EVERY = 20L;

    private static final boolean BRIDGE_DEBUG = Boolean.getBoolean("folkways.bridgeDebug");

    private static final int MOST_ANSWERING_ROUNDS = 8;

    private static final int MOST_GOALS_TRIED = 2;

    private static final long EXPIRE_EVERY = 20L;

    private static final long WALKING_TTL = 60L;

    private static final long PLANNING_TTL = 1_200L;

    private static final long BACKGROUND_TTL = 600L;

    private record Shelf(Ask ask, long until) {
    }

    private record Fresh(Tract tract, long footing) {
    }

    // What was rambled from lately and to no end, each left alone twice as long as the time before.
    private static final class Rests {

        private static final int MOST_REMEMBERED = 4_096;

        private final Long2LongOpenHashMap until = new Long2LongOpenHashMap();
        private final Long2IntOpenHashMap tries = new Long2IntOpenHashMap();

        boolean resting(long key) {
            return until.containsKey(key);
        }

        void rest(long key, long now) {
            if (tries.size() >= MOST_REMEMBERED) {
                tries.clear();
            }
            int tried = tries.addTo(key, 1);
            until.put(key, now + (RAMBLED_TICKS << Math.min(tried, MOST_BRIDGE_BACKOFFS)));
        }

        void wake(long now) {
            until.long2LongEntrySet().removeIf(entry -> entry.getLongValue() <= now);
        }
    }

    // One net's bridging: the gaps tried, the lone footings swept from, and the pieces not to be read again yet.
    private static final class Bridging {

        private final Rests gaps = new Rests();
        private final Rests footings = new Rests();
        private final Long2LongOpenHashMap pieces = new Long2LongOpenHashMap();

        void wake(long now) {
            gaps.wake(now);
            footings.wake(now);
            pieces.long2LongEntrySet().removeIf(entry -> entry.getLongValue() <= now);
        }
    }

    private final ServerLevel level;

    private final Realm home;

    private final Asked asked = new Asked();

    private final Trodden trodden = new Trodden();

    private final Deque<Trod> walked = new ArrayDeque<>();

    private final Long2LongOpenHashMap rambledUntil = new Long2LongOpenHashMap();

    // What each net was bridged from lately.
    private final Map<Tract, Bridging> bridging = new LinkedHashMap<>();
    private long nextFreeze;
    private long nextIdleRamble;

    // Platforms new to a net, by a footing to sweep round from, waiting for their sweep. A work site new to a net
    // waits here too.
    private final Set<Fresh> platforms = new LinkedHashSet<>();

    // Where a body stands to work each of the colony's work sites, by the site's block, reckoned once.
    private final Long2ObjectOpenHashMap<long[]> footingsOf = new Long2ObjectOpenHashMap<>();

    // The work sites each net has pinned the footings of.
    private final Map<Tract, LongOpenHashSet> sited = new LinkedHashMap<>();

    private final Pending pending = new Pending();

    private final Pending connecting = new Pending();

    private final Map<Ask.Key, Shelf> shelved = new LinkedHashMap<>();

    private final Map<Tract, Waypoints> nets = new LinkedHashMap<>();

    private final Map<Tract, Waypoints.Net> frozen = new LinkedHashMap<>();

    private final Map<Realm, TravelExtent> extents = new LinkedHashMap<>();

    private long receivedRequests;
    private long enqueuedRequests;
    private long answeredRequests;
    private long startedRequests;
    private long discardedRequests;
    private long reviewedRequests;
    private long sweptRequests;
    private long nextRamble;
    private long nextBridge;
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

    // Whether any kind's ways hold the cell as a point of their own.
    boolean holds(long cell) {
        return nets.values().stream().anyMatch(net -> net.knows(cell));
    }

    public boolean advance(List<Roamer> roamers, List<Hop> hops, Collection<WorldPos> sites, long now) {
        return advance(roamers, hops, sites, now, Slice.forWindow(), true);
    }

    public Faring answered(Function<Ways, Faring> asking, List<Roamer> roamers, List<Hop> hops,
                           Collection<WorldPos> sites, long now) {
        Faring said = asking.apply(published);
        for (int round = 0; said instanceof Faring.Later && round < MOST_ANSWERING_ROUNDS; round++) {
            if (!answer(roamers, hops, sites, now)) {
                break;
            }
            said = asking.apply(published);
        }
        return said instanceof Faring.Later ? Faring.NO : said;
    }

    private boolean answer(List<Roamer> roamers, List<Hop> hops, Collection<WorldPos> sites, long now) {
        Set<ResourceLocation> knew = published.kinds();
        boolean asking = advance(roamers, hops, sites, now, Slice.UNTIL_ANSWERED, false);
        return asking || !published.kinds().equals(knew);
    }

    private boolean advance(List<Roamer> roamers, List<Hop> hops, Collection<WorldPos> sites, long now,
                            Slice slice, boolean wandering) {
        Map<ResourceLocation, Sort> sorts = sortsOf(roamers);
        List<Crossing> crossings = crossingsOf(hops);
        forget(sorts, crossings);
        close();
        extents.values().forEach(extent -> extent.roll(now));
        wire(sorts, roamers, crossings, now);
        site(sorts, sites, now);
        reconsider(sorts, now);
        List<Ask> fresh = new ArrayList<>();
        for (Ask ask : asked.drain()) {
            receivedRequests++;
            TravelExtent extent = extent(ask.realm());
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
            TravelExtent extent = extent(held.getKey().realm());
            held.getValue().room(extent.ground(), extent.wanted(), extent.layers());
        }
        if (now >= nextExpiry) {
            expire(now);
            nextExpiry = now + EXPIRE_EVERY;
        }
        PathfindingLoad.asksPending(pending.size() + connecting.size());
        if (wandering && now >= nextAskDump) {
            nextAskDump = now + 600L;
            dumpAsks(now);
        }
        boolean asking = !pending.isEmpty() || !connecting.isEmpty() || quest != null;
        long began = Util.getNanos();
        if (slice == Slice.UNTIL_ANSWERED) {
            for (Ask ask : fresh) {
                if (pending.remove(ask)) {
                    settleNow(ask, sorts, roamers, now);
                }
            }
        } else {
            // Loose pieces are bridged on a reserve of their own, before the asks: the asks take every slice there is.
            if (wandering && now >= nextBridge) {
                bridge(sorts, roamers, slice, now);
                nextBridge = now + BRIDGE_EVERY;
            }
            boolean explored = wandering && now >= nextRamble
                && wander(sorts, roamers, slice, now, true);
            if (explored) {
                nextRamble = now + RAMBLE_EVERY;
            }
            if (!explored || !slice.spent(0)) {
                work(sorts, roamers, now, slice);
            }
            // Idle time explores too, but no more often than walked ground is rambled: each ramble grows the ways the
            // planner's every freeze reads.
            if (wandering && !explored && now >= nextIdleRamble && quest == null && pending.isEmpty()
                    && connecting.isEmpty()) {
                if (wander(sorts, roamers, slice, now, false)) {
                    nextRamble = now + RAMBLE_EVERY;
                }
                nextIdleRamble = now + RAMBLE_EVERY;
            }
        }

        publish(sorts, roamers, crossings, now, slice == Slice.UNTIL_ANSWERED);

        TravelBudget.spent(Util.getNanos() - began);
        return asking;
    }

    private long nextAskDump;

    // Diagnostic: what the asks waiting are, by kind and urgency, with a few of each queue spelled out.
    private void dumpAsks(long now) {
        Map<String, Integer> byKind = new java.util.TreeMap<>();
        for (Ask ask : pending) {
            byKind.merge("p:" + ask.kind().getPath() + "/" + ask.urgency().band(), 1, Integer::sum);
        }
        for (Ask ask : connecting) {
            byKind.merge("c:" + ask.kind().getPath() + "/" + ask.urgency().band(), 1, Integer::sum);
        }
        StringBuilder said = new StringBuilder("asks waiting pending=" + pending.size() + " connecting="
            + connecting.size() + " shelved=" + shelved.size() + " quest="
            + (quest == null ? "-" : spell(quest.ask())) + " " + byKind + " counts recv=" + receivedRequests
            + " enq=" + enqueuedRequests + " ans=" + answeredRequests + " started=" + startedRequests
            + " discarded=" + discardedRequests + " reviewed=" + reviewedRequests);
        int shown = 0;
        for (Ask ask : pending) {
            if (shown++ >= 6) {
                break;
            }
            said.append("\n  p ").append(spell(ask));
        }
        shown = 0;
        for (Ask ask : connecting) {
            if (shown++ >= 6) {
                break;
            }
            said.append("\n  c ").append(spell(ask));
        }
        LOGGER.info("{}", said);
    }

    private String spell(Ask ask) {
        BlockPos goal = BlockPos.of(ask.goals().getLong(0));
        String on = "";
        for (Map.Entry<Tract, Waypoints> held : nets.entrySet()) {
            if (held.getKey().realm().equals(ask.realm())) {
                Waypoints net = held.getValue();
                int a = net.at(ask.from());
                int b = net.at(ask.goals().getLong(0));
                on = " fromOn=" + (a >= 0) + " goalOn=" + (b >= 0);
                break;
            }
        }
        return ask.kind().getPath() + " " + ask.urgency() + " " + ask.start().toShortString() + " -> "
            + goal.toShortString() + " (" + ask.goals().size() + " goals)" + on;
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
        TravelExtent extent = extent(ask.realm());
        net.room(extent.ground(), extent.wanted(), extent.layers());
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
            PathfindingLoad.rambled(reserved ? "walked" : "idle", ramble.run(now, reserved ? BUSY_RAMBLE_VISITS
                : Sweep.MOST_VISITED));
            ran = true;
            boolean exhausted = slice.spent(ramble.spent());
            if (reserved || exhausted) {
                return true;
            }
        }
        return ran;
    }

    private void bridge(Map<ResourceLocation, Sort> sorts, List<Roamer> roamers, Slice slice, long now) {
        for (Iterator<Fresh> waiting = platforms.iterator(); waiting.hasNext(); ) {
            Fresh platform = waiting.next();
            waiting.remove();
            Waypoints net = nets.get(platform.tract());
            Roamer roamer = roamerOf(roamers, sorts, platform.tract().sort());
            if (net == null || roamer == null) {
                continue;
            }
            Ramble ramble = new Ramble(level, platform.tract().realm(), net, roamer, platform.footing());
            PathfindingLoad.rambled("platform", ramble.run(now, Sweep.MOST_VISITED));
            if (slice.spent(ramble.spent())) {
                return;
            }
        }
        for (Map.Entry<Tract, Waypoints> held : nets.entrySet()) {
            Roamer roamer = roamerOf(roamers, sorts, held.getKey().sort());
            if (roamer == null) {
                continue;
            }
            Waypoints net = held.getValue();
            Bridging rests = bridging.computeIfAbsent(held.getKey(), tract -> new Bridging());
            rests.wake(now);
            for (long footing : net.lonely(MOST_FOOTINGS, rests.footings::resting)) {
                rests.footings.rest(footing, now);
                Ramble ramble = new Ramble(level, held.getKey().realm(), net, roamer, footing);
                int laid = ramble.run(now, FOOTING_VISITS);
                PathfindingLoad.rambled("footing", laid);
                if (BRIDGE_DEBUG) {
                    LOGGER.info("footing {} {} laid={} spent={}", held.getKey().sort().bulk(),
                        BlockPos.of(footing).toShortString(), laid, ramble.spent());
                }
                if (slice.spent(ramble.spent())) {
                    return;
                }
            }
            for (long[] gap : net.bridges(MOST_BRIDGES, rests.pieces::containsKey, rests.gaps::resting,
                    piece -> rests.pieces.put(piece, now + PIECE_RESTS))) {
                rests.pieces.put(gap[2], now + PIECE_RESTS);
                rests.gaps.rest(gap[3], now);
                Ramble ramble = new Ramble(level, held.getKey().realm(), net, roamer, gap[0], gap[1]);
                int laid = ramble.run(now, Sweep.MOST_VISITED);
                PathfindingLoad.rambled("bridge", laid);
                if (BRIDGE_DEBUG) {
                    int a = net.at(gap[0]), b = net.at(gap[1]);
                    LOGGER.info("bridge {} {} -> {} laid={} spent={} joined={}", held.getKey().sort().bulk(),
                        BlockPos.of(gap[0]).toShortString(), BlockPos.of(gap[1]).toShortString(), laid, ramble.spent(),
                        a >= 0 && b >= 0 && net.joins(a, b, now));
                }
                if (laid == 0 && ramble.spent() <= 1) {
                    net.stranded(gap[0]);
                }
                if (slice.spent(ramble.spent())) {
                    return;
                }
            }
        }
    }

    private static Roamer roamerOf(List<Roamer> roamers, Map<ResourceLocation, Sort> sorts, Sort sort) {
        for (Map.Entry<ResourceLocation, Sort> kind : sorts.entrySet()) {
            if (kind.getValue().equals(sort)) {
                Roamer roamer = roamerOf(roamers, kind.getKey());
                if (roamer != null && !roamer.body().isRemoved()) {
                    return roamer;
                }
            }
        }
        return null;
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
                Tract boarding = new Tract(sort, crossing.boarding().realm());
                Tract landing = new Tract(sort, crossing.landing().realm());
                platform(net(sort, boarding.realm()), boarding, crossing.hop().boarding(), crossing.boarding(), now);
                platform(net(sort, landing.realm()), landing, crossing.hop().landing(), crossing.landing(), now);
            }
        }
    }

    // A platform's footings stand in the ways as points like any other, joined to them only by ways walked: what is
    // "aboard" is the web's, not a cell. A platform not seen before is swept round once, so its footings join up.
    private void platform(Waypoints net, Tract tract, Stances.Cells footings, WorldPos anchor, long now) {
        boolean fresh = false;
        long first = Long.MIN_VALUE;
        for (WorldPos cell : footings.cells()) {
            if (!cell.sameRealm(anchor) || shut(cell)) {

                continue;
            }
            fresh |= !net.knows(cell.cell().asLong());
            net.pin(cell.cell().asLong(), now);
            if (first == Long.MIN_VALUE) {
                first = cell.cell().asLong();
            }
        }
        // Swept from a footing, where a body stands: the anchor may be on the track.
        if (fresh && first != Long.MIN_VALUE) {
            platforms.add(new Fresh(tract, first));
        }
    }

    // A work site the colony holds joins the ways as a platform does: the footings a body works it from are pinned,
    // and swept round from once if the ways do not know them, so a body sent to work there is routed even from
    // ground no one has walked. Sites in the level's own frame only: a footing is reckoned by the level's blocks.
    private void site(Map<ResourceLocation, Sort> sorts, Collection<WorldPos> sites, long now) {
        LongOpenHashSet held = new LongOpenHashSet();
        for (WorldPos site : sites) {
            if (site.realm().equals(home) && !shut(site)) {
                held.add(site.cell().asLong());
            }
        }
        footingsOf.keySet().removeIf((long site) -> !held.contains(site));
        sited.keySet().removeIf(tract -> !nets.containsKey(tract) || !sorts.containsValue(tract.sort()));
        sited.values().forEach(pinned -> pinned.removeIf((long site) -> !held.contains(site)));
        int reckoned = 0;
        for (Sort sort : sorts.values()) {
            Tract tract = new Tract(sort, home);
            Waypoints net = net(sort, home);
            LongOpenHashSet pinned = sited.computeIfAbsent(tract, key -> new LongOpenHashSet());
            for (long site : held) {
                if (pinned.contains(site)) {
                    continue;
                }
                long[] footings = footingsOf.get(site);
                if (footings == null) {
                    if (reckoned >= MOST_SITES_RECKONED || !level.isLoaded(BlockPos.of(site))) {
                        continue;
                    }
                    reckoned++;
                    footings = Reach.workableCells(level, BlockPos.of(site)).stream()
                        .filter(cell -> !shut(new WorldPos(home, cell)))
                        .mapToLong(BlockPos::asLong).toArray();
                    footingsOf.put(site, footings);
                }
                pinned.add(site);
                boolean fresh = false;
                for (long footing : footings) {
                    fresh |= !net.knows(footing);
                    net.pin(footing, now);
                }
                if (fresh) {
                    platforms.add(new Fresh(tract, footings[0]));
                }
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

    private boolean shut(WorldPos cell) {
        return closed.stream().anyMatch(one -> one.holds(cell));
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

    private TravelExtent extent(Realm realm) {
        return extents.computeIfAbsent(realm, where -> new TravelExtent());
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
        bridging.keySet().removeIf(gone);
        platforms.removeIf(fresh -> gone.test(fresh.tract()));
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

    // The ways as the planner reads them: each net frozen afresh when it changed - at once while asks are answered
    // on the spot, at most every FREEZE_EVERY ticks otherwise, as a freeze reads the whole net.
    private void publish(Map<ResourceLocation, Sort> sorts, List<Roamer> roamers,
                         List<Crossing> crossings, long now, boolean promptly) {
        int points = 0;
        int links = 0;
        long straightening = 0;
        long freezing = 0;
        boolean due = promptly || now >= nextFreeze;
        boolean froze = false;
        for (Map.Entry<Tract, Waypoints> held : nets.entrySet()) {
            Waypoints net = held.getValue();
            if (net.trim(now)) {
                PathfindingLoad.renumbered();
            }
            if (due && quest == null && net.changed(now)) {
                long began = Util.getNanos();
                PathfindingLoad.straightened(net.straighten(now));
                straightening += Util.getNanos() - began;
            }
            if (due && net.changed(now) || !frozen.containsKey(held.getKey())) {
                froze = true;
                long began = Util.getNanos();
                frozen.put(held.getKey(), net.freeze(now));
                freezing += Util.getNanos() - began;
            }
            points += net.size();
            links += net.links();
        }
        if (froze) {
            nextFreeze = now + FREEZE_EVERY;
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

}
