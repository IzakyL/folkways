package io.github.izakyl.folkways.core.engine.travel.graph;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;

final class Sweep {

    static final float RANGE = 64.0F;

    static final int MARGIN = 8;

    static final int MOST_VISITED = 1_024;

    private static final float WEIGHT = 1.5F;

    record Swept(List<Node> nodes, List<Node> walked, boolean reached, boolean enclosed,
                 Bounds ground, int visited) {
    }

    private Sweep() {
    }

    static Swept from(PathNavigationRegion region, Mob sizing, NodeEvaluator evaluator,
                      Set<BlockPos> arriving, BlockPos aim, boolean wide) {
        return from(region, sizing, evaluator, arriving, aim, wide, MOST_VISITED);
    }

    static Swept from(PathNavigationRegion region, Mob sizing, NodeEvaluator evaluator,
                      Set<BlockPos> arriving, BlockPos aim, boolean wide, int limit) {
        return from(region, sizing, evaluator, arriving, cell -> false, aim, wide, limit);
    }

    static Swept from(PathNavigationRegion region, Mob sizing, NodeEvaluator evaluator,
                      Set<BlockPos> arriving, LongPredicate landing, BlockPos aim, boolean wide, int limit) {
        return from(region, sizing, evaluator, arriving, landing, cell -> false, aim, wide, limit);
    }

    // `shut` is ground the ways are closed over: nothing is swept into it, and a sweep begun in it finds nothing.
    static Swept from(PathNavigationRegion region, Mob sizing, NodeEvaluator evaluator,
                      Set<BlockPos> arriving, LongPredicate landing, LongPredicate shut, BlockPos aim, boolean wide,
                      int limit) {
        limit = Math.clamp(limit, 1, MOST_VISITED);
        LongOpenHashSet goals = new LongOpenHashSet(arriving.size());
        for (BlockPos one : arriving) {
            goals.add(one.asLong());
        }
        BinaryHeap open = new BinaryHeap();
        Node[] buffer = new Node[32];

        int step = (int) Math.max(1.0F, sizing.maxUpStep());
        evaluator.prepare(region, sizing);
        try {
            Node start = evaluator.getStart();
            if (start == null || shut.test(BlockPos.asLong(start.x, start.y, start.z))) {
                return new Swept(List.of(), List.of(), false, false, null, 0);
            }
            List<Node> settled = wide ? new ArrayList<>() : List.of();
            start.g = 0.0F;

            start.h = wide ? 0.0F : heuristic(start, aim) * WEIGHT;
            start.f = start.h;
            open.insert(start);
            Node nearest = start;
            Node arrived = null;
            boolean limited = false;
            int visited = 0;
            Bounds ground = Bounds.around(start.x, start.y, start.z);
            while (!open.isEmpty() && visited < limit) {
                Node node = open.pop();
                node.closed = true;
                visited++;
                if (wide) {
                    settled.add(node);
                }

                ground = ground.reaching(node.x, node.y, node.z);
                if (node.h < nearest.h) {
                    nearest = node;
                }
                long cell = BlockPos.asLong(node.x, node.y, node.z);
                if (goals.contains(cell) || (node != start && landing.test(cell))) {
                    arrived = node;
                    break;
                }
                if (node.distanceTo(start) >= RANGE) {
                    limited = true;
                    continue;
                }
                int count = evaluator.getNeighbors(buffer, node);
                for (int at = 0; at < count; at++) {
                    Node next = buffer[at];
                    if (next.y < node.y - step || shut.test(BlockPos.asLong(next.x, next.y, next.z))) {

                        continue;
                    }
                    float apart = node.distanceTo(next);
                    float walked = node.walkedDistance + apart;
                    if (walked >= RANGE) {
                        limited = true;
                        continue;
                    }
                    float cost = node.g + apart + next.costMalus;
                    if (next.inOpenSet() && cost >= next.g) {
                        continue;
                    }
                    next.walkedDistance = walked;
                    next.cameFrom = node;
                    next.g = cost;
                    next.h = wide ? 0.0F : heuristic(next, aim) * WEIGHT;
                    if (next.inOpenSet()) {
                        open.changeCost(next, next.g + next.h);
                    } else {
                        next.f = next.g + next.h;
                        open.insert(next);
                    }
                }
            }
            boolean enclosed = arrived == null && open.isEmpty() && !limited
                && visited < limit;
            return new Swept(trail(arrived == null ? nearest : arrived), settled, arrived != null,
                enclosed, ground, visited);
        } finally {
            evaluator.done();
        }
    }

    private static float heuristic(Node node, BlockPos goal) {
        float dx = goal.getX() - node.x;
        float dy = goal.getY() - node.y;
        float dz = goal.getZ() - node.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<Node> trail(Node last) {
        List<Node> back = new ArrayList<>();
        for (Node node = last; node != null; node = node.cameFrom) {
            back.add(node);
        }
        Collections.reverse(back);
        return back;
    }
}
