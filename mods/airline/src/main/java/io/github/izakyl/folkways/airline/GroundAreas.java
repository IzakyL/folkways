package io.github.izakyl.folkways.airline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** A conservative distance transform and widest paths on the actual pavement raster. */
final class GroundAreas {
    private GroundAreas() {
    }

    static GroundGraph build(AirportSurface surface, List<BlockPos> cells, List<Runway> runways,
                             List<Helipad> pads, List<Airport.Stand> stands) {
        Set<BlockPos> pavement = new HashSet<>(cells.stream().filter(surface::pavement).toList());
        Map<BlockPos, Integer> distance = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos pos : cells) {
            boolean boundary = false;
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    boundary |= !pavement.contains(pos.offset(x, 0, z));
                }
            }
            if (boundary) {
                distance.put(pos, 1);
                queue.add(pos);
            }
        }
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos next = pos.offset(x, 0, z);
                    if (pavement.contains(next) && !distance.containsKey(next)) {
                        distance.put(next, distance.get(pos) + 1);
                        queue.add(next);
                    }
                }
            }
        }
        List<GroundGraph.Node> nodes = new ArrayList<>();
        for (Airport.Stand stand : stands) {
            nodes.add(new GroundGraph.Node(stand.pos(), Set.of(GroundGraph.Kind.STAND), stand.pos()));
        }
        for (Helipad pad : pads) {
            nodes.add(new GroundGraph.Node(pad.center(), Set.of(GroundGraph.Kind.HELIPAD), pad.center()));
        }
        // Facilities connect through their interior; the path crossing the edge supplies its entry naturally.
        for (Runway runway : runways) {
            BlockPos center = runway.cell(runway.distance() / 2, 0);
            nodes.add(new GroundGraph.Node(center, Set.of(GroundGraph.Kind.RUNWAY_ENTRY), runway.firstThreshold(),
                runway.length() / 2, runway.length() / 2, 0));
        }
        List<GroundGraph.Edge> edges = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            GroundGraph.Node start = nodes.get(i);
            Map<BlockPos, Label> labels = paths(surface, pavement, distance, start.pos());
            for (int j = i + 1; j < nodes.size(); j++) {
                BlockPos end = nodes.get(j).pos();
                Label label = labels.get(end);
                if (label == null || label.span() <= 0) {
                    continue;
                }
                List<BlockPos> path = new ArrayList<>();
                BlockPos current = end;
                while (current != null) {
                    path.add(current);
                    current = labels.get(current).previous();
                }
                path = new ArrayList<>(path.reversed());
                edges.add(new GroundGraph.Edge(start.pos(), end, pathLength(path), path, label.span()));
            }
        }
        return new GroundGraph(nodes, edges, List.of(), true, cells);
    }

    private static int pathLength(List<BlockPos> path) {
        double length = 0;
        for (int i = 1; i < path.size(); i++) {
            length += Math.sqrt(path.get(i - 1).distSqr(path.get(i)));
        }
        return (int) Math.round(length);
    }

    private record Label(BlockPos pos, BlockPos previous, double span, double cost) {
    }

    private static Map<BlockPos, Label> paths(AirportSurface surface, Set<BlockPos> pavement,
                                              Map<BlockPos, Integer> distance, BlockPos start) {
        Map<BlockPos, Label> labels = new HashMap<>();
        PriorityQueue<Label> queue = new PriorityQueue<>(Comparator.comparingDouble(Label::span).reversed()
            .thenComparingDouble(Label::cost).thenComparingLong(label -> label.pos().asLong()));
        Label first = new Label(start, null, span(distance, start), 0);
        labels.put(start, first);
        queue.add(first);
        while (!queue.isEmpty()) {
            Label current = queue.remove();
            if (labels.get(current.pos()) != current) {
                continue;
            }
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0) {
                        continue;
                    }
                    BlockPos next = current.pos().offset(x, 0, z);
                    if (!pavement.contains(next)) {
                        continue;
                    }
                    double span = Math.min(current.span(), span(distance, next));
                    if (x != 0 && z != 0) {
                        span = Math.min(span, Math.min(span(distance, current.pos().offset(x, 0, 0)),
                            span(distance, current.pos().offset(0, 0, z))));
                    }
                    double cost = current.cost() + Math.hypot(x, z) * (1 + 2.0 / distance.get(next))
                        + (surface.material(next) == AirportSurface.YELLOW ? 0 : 0.3);
                    Label old = labels.get(next);
                    if (old == null || span > old.span() || span == old.span() && cost < old.cost()) {
                        Label label = new Label(next, current.pos(), span, cost);
                        labels.put(next, label);
                        queue.add(label);
                    }
                }
            }
        }
        return labels;
    }

    private static double span(Map<BlockPos, Integer> distance, BlockPos pos) {
        return Math.max(0, 2 * distance.getOrDefault(pos, 0) - 3);
    }
}
