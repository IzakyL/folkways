package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.terms.Gait;
import io.github.izakyl.folkways.core.engine.travel.PathfindingLoad;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Node;

final class Quest {

    private static final int MOST_STEPS = 96;

    enum State {
        GOING,
        FOUND,
        DENIED,
        GAVE_UP
    }

    private record Step(int id, int g, int f) {
    }

    private final ServerLevel level;
    private final Waypoints net;
    private final Roamer roamer;
    private final Ask ask;
    private final int era;

    private final Set<BlockPos> goals = new LinkedHashSet<>();

    private final Deque<BlockPos> unprobed = new ArrayDeque<>();

    private final PriorityQueue<Step> open =
        new PriorityQueue<>(Comparator.comparingInt(Step::f));
    private final IntSet closed = new IntOpenHashSet();
    private final Int2IntOpenHashMap best = new Int2IntOpenHashMap();

    private int steps;

    private boolean guessed;

    private boolean gone;

    private boolean opened;
    private int origin = -1;
    private int spent;

    private PathfindingLoad.Denial denial;

    Quest(ServerLevel level, Waypoints net, Roamer roamer, Ask ask, long now) {
        this.level = level;
        this.net = net;
        this.roamer = roamer;
        this.ask = ask;
        this.era = net.era();
        this.best.defaultReturnValue(Integer.MAX_VALUE);
        for (int id : net.pinSite(ask.goals(), now)) {
            BlockPos cell = BlockPos.of(net.cellOf(id));
            if (goals.add(cell)) {
                unprobed.addLast(cell);
            }
        }
    }

    Ask ask() {
        return ask;
    }

    PathfindingLoad.Denial denial() {
        return denial;
    }

    private State denied(PathfindingLoad.Denial why) {
        denial = why;
        return State.DENIED;
    }

    int spent() {
        return spent;
    }

    boolean stale() {
        return net.era() != era || gone || roamer.body().isRemoved()
            || roamer.body().level() != level;
    }

    State step(long now) {
        spent = 0;
        if (stale()) {
            return State.GAVE_UP;
        }
        if (goals.isEmpty()) {

            return denied(PathfindingLoad.Denial.NO_GOALS);
        }
        if (!opened) {
            opened = true;
            origin = net.admit(ask.from(), now);
            offer(origin, 0);
        }

        if (connected(now)) {
            return State.FOUND;
        }
        return steps >= 1 && !unprobed.isEmpty() ? probeGoal(now) : reachOut(now);
    }

    private State probeGoal(long now) {
        BlockPos goal = unprobed.pollFirst();
        Reading read = search(goal.asLong(), Set.of(ask.start()), ask.start());
        if (read == null) {
            return State.GAVE_UP;
        }
        Sweep.Swept swept = read.swept();
        if (swept.nodes().isEmpty()) {
            return more();
        }

        int at = net.pin(goal.asLong(), now);
        boolean arrived = harvest(read, at, 0, false, now);
        seal(read, at);
        if (arrived || connected(now)) {
            return State.FOUND;
        }
        if (swept.enclosed()) {

            goals.remove(goal);
            if (goals.isEmpty()) {
                return denied(PathfindingLoad.Denial.FAR_END);
            }
        }
        return more();
    }

    private State reachOut(long now) {
        Step here = pop();
        if (here == null) {

            return guessed ? State.GAVE_UP : denied(PathfindingLoad.Denial.FRONTIER);
        }
        closed.add(here.id());
        Reading read = search(net.cellOf(here.id()), goals, nearestGoal(here.id()));
        if (read == null) {
            return State.GAVE_UP;
        }
        Sweep.Swept swept = read.swept();
        if (swept.nodes().isEmpty()) {

            net.drop(here.id());
            return more();
        }
        boolean arrived = harvest(read, here.id(), here.g(), true, now);
        seal(read, here.id());
        relax(here, now);
        if (arrived || connected(now)) {
            return State.FOUND;
        }

        if (swept.enclosed() && here.id() == origin) {
            return denied(PathfindingLoad.Denial.NEAR_END);
        }
        return more();
    }

    private boolean connected(long now) {
        IntArrayList ends = new IntArrayList(goals.size());
        for (BlockPos goal : goals) {
            ends.add(net.reaching(goal.asLong()));
        }
        return net.joinsAny(origin, ends, now);
    }

    private void seal(Reading read, int at) {
        if (read.swept().enclosed() && read.on() instanceof Ground.Open) {
            net.seal(at, read.swept().ground());
        }
    }

    private State more() {
        return ++steps >= MOST_STEPS ? State.GAVE_UP : State.GOING;
    }

    private Step pop() {
        while (!open.isEmpty()) {
            Step step = open.poll();
            if (!closed.contains(step.id()) && step.g() <= best.get(step.id())) {
                return step;
            }
        }
        return null;
    }

    private void offer(int id, int g) {
        if (closed.contains(id) || g >= best.get(id)) {
            return;
        }
        best.put(id, g);
        open.add(new Step(id, g, g + heuristic(id)));
    }

    private int heuristic(int id) {
        BlockPos here = BlockPos.of(net.cellOf(id));
        int least = Integer.MAX_VALUE;
        for (BlockPos goal : goals) {
            least = Math.min(least, Waypoints.Net.ticks(here, goal));
        }
        return least;
    }

    private BlockPos nearestGoal(int id) {
        BlockPos here = BlockPos.of(net.cellOf(id));
        BlockPos best = null;
        double closest = Double.MAX_VALUE;
        for (BlockPos goal : goals) {
            double apart = goal.distSqr(here);
            if (apart < closest) {
                closest = apart;
                best = goal;
            }
        }
        return best;
    }

    private void relax(Step here, long now) {
        for (Waypoints.Link link : net.linksFrom(here.id())) {
            if (link.live(now)) {
                offer(link.to, here.g() + link.ticks);
            }
        }
    }

    private Reading search(long from, Set<BlockPos> arriving, BlockPos aim) {
        Optional<Reading> read = Look.at(level, ask.realm(), roamer, from, arriving, aim, false);
        if (read.isEmpty()) {
            gone = true;
            return null;
        }
        Sweep.Swept swept = read.get().swept();
        spent = swept.visited();
        guessed |= !swept.enclosed() && !swept.reached();
        return read.get();
    }

    private boolean harvest(Reading read, int from, int carried, boolean offering, long now) {
        Sweep.Swept swept = read.swept();
        int previous = from;
        BlockPos last = BlockPos.of(net.cellOf(from));
        double run = 0.0D;
        long[] waiting = new long[swept.nodes().size()];
        int held = 0;
        for (int at = 0; at < swept.nodes().size(); at++) {
            Node node = swept.nodes().get(at);
            Optional<BlockPos> mine = read.on().toLocal(new BlockPos(node.x, node.y, node.z));
            if (mine.isEmpty()) {

                return false;
            }
            BlockPos cell = mine.get();
            run += Math.sqrt(last.distSqr(cell));
            last = cell;
            boolean end = at == swept.nodes().size() - 1;
            if (run < Waypoints.SPACING && !end) {
                waiting[held++] = cell.asLong();
                continue;
            }
            int cost = Math.max(1, (int) Math.round(run / Gait.BLOCKS_PER_TICK));
            int id = net.admit(cell.asLong(), now);
            if (id != previous) {
                net.link(previous, id, cost, now);
                net.link(id, previous, cost, now);
                carried += cost;
                if (offering) {
                    offer(id, carried);
                }
            }
            for (int waited = 0; waited < held; waited++) {
                net.remember(waiting[waited], id, now);
            }
            held = 0;
            previous = id;
            run = 0.0D;
        }
        return swept.reached();
    }
}
