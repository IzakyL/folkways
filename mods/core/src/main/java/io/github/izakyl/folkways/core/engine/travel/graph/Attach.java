package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.terms.Gait;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.engine.travel.PathfindingLoad;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Node;

final class Attach {

    static final int MOST_VISITED = 512;

    enum Outcome {
        ON,
        LAID,
        CLOSED,
        FAR
    }

    record Result(Outcome outcome, int visited) {
    }

    private Attach() {
    }

    static Result end(ServerLevel level, Waypoints net, Roamer roamer, Realm realm, long end,
                      Set<BlockPos> others, BlockPos aim, long now) {
        if (net.near(end, now) >= 0) {
            return new Result(Outcome.ON, 0);
        }
        Optional<Reading> read = Look.at(level, realm, roamer, end, others, net::knows, aim, false, MOST_VISITED);
        if (read.isEmpty()) {
            PathfindingLoad.attachFar();
            return new Result(Outcome.FAR, 0);
        }
        Sweep.Swept swept = read.get().swept();
        if (swept.reached() && lay(net, read.get(), now)) {
            PathfindingLoad.attachLaid();
            return new Result(Outcome.LAID, swept.visited());
        }
        if (swept.enclosed() && read.get().on() instanceof Ground.Open) {
            net.seal(net.pin(end, now), swept.ground());
            PathfindingLoad.attachClosed();
            return new Result(Outcome.CLOSED, swept.visited());
        }
        PathfindingLoad.attachFar();
        return new Result(Outcome.FAR, swept.visited());
    }

    private static boolean lay(Waypoints net, Reading read, long now) {
        List<BlockPos> trail = new ArrayList<>(read.swept().nodes().size());
        for (Node node : read.swept().nodes()) {
            Optional<BlockPos> mine = read.on().toLocal(new BlockPos(node.x, node.y, node.z));
            if (mine.isEmpty()) {
                return false;
            }
            trail.add(mine.get());
        }
        if (trail.isEmpty()) {
            return false;
        }
        BlockPos last = trail.getLast();
        int previous = net.admit(last.asLong(), now);
        LongArrayList waiting = new LongArrayList();
        double run = 0.0D;
        for (int at = trail.size() - 2; at >= 0; at--) {
            BlockPos cell = trail.get(at);
            run += Math.sqrt(last.distSqr(cell));
            last = cell;
            if (run < Waypoints.SPACING || at == 0) {
                waiting.add(cell.asLong());
                continue;
            }
            int id = net.admit(cell.asLong(), now);
            if (id != previous) {
                int cost = ticks(run);
                net.link(previous, id, cost, now);
                net.link(id, previous, cost, now);
            }
            for (int held = 0; held < waiting.size(); held++) {
                net.remember(waiting.getLong(held), id, now);
            }
            waiting.clear();
            previous = id;
            run = 0.0D;
        }
        for (int held = 0; held < waiting.size(); held++) {
            net.remember(waiting.getLong(held), previous, now);
        }
        long end = trail.getFirst().asLong();
        if (net.reaching(end) < 0) {
            int id = net.admit(end, now);
            if (id != previous) {
                int cost = ticks(Math.max(run, 1.0D));
                net.link(previous, id, cost, now);
                net.link(id, previous, cost, now);
            }
        }
        return true;
    }

    private static int ticks(double blocks) {
        return Math.max(1, (int) Math.round(blocks / Gait.BLOCKS_PER_TICK));
    }
}
