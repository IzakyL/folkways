package io.github.izakyl.folkways.airline;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public record GroundGraph(List<Node> nodes, List<Edge> edges, List<Turn> turns, boolean graded, List<BlockPos> pavement) {
    public GroundGraph(List<Node> nodes, List<Edge> edges, List<Turn> turns, boolean graded) {
        this(nodes, edges, turns, graded, List.of());
    }

    public GroundGraph(List<Node> nodes, List<Edge> edges) {
        this(nodes, edges, List.of(), false);
    }

    public enum Kind { RUNWAY_ENTRY, HOLDING_POINT, STAND, HELIPAD, JUNCTION, TAXIWAY }

    public record Node(BlockPos pos, Set<Kind> kinds, BlockPos facility, int remainingFirst, int remainingSecond,
                       double holdingDistance) {
        public Node(BlockPos pos, Set<Kind> kinds, BlockPos facility) {
            this(pos, kinds, facility, 0, 0, 0);
        }

        public Node {
            kinds = Set.copyOf(kinds);
        }
    }

    public record Edge(BlockPos from, BlockPos to, int length, List<BlockPos> cells, double maxWingspan) {
        public Edge(BlockPos from, BlockPos to, int length, List<BlockPos> cells) {
            this(from, to, length, cells, 0);
        }

        public Edge {
            cells = List.copyOf(cells);
        }
    }

    // Directions point away from the vertex. Radii are verified at this wingspan, including the pavement margin.
    public record Turn(BlockPos pos, Direction first, Direction second,
                       double maxWingspan, double minRadius, double maxRadius) {
    }

    public GroundGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        turns = List.copyOf(turns);
        pavement = List.copyOf(pavement);
    }

    public static GroundGraph empty() {
        return new GroundGraph(List.of(), List.of());
    }

    public boolean reaches(BlockPos start, Set<BlockPos> facilities) {
        return reachableFrom(facilities).contains(start);
    }

    public Set<BlockPos> reachableFrom(Set<BlockPos> facilities) {
        return reachableFrom(facilities, 0);
    }

    public Set<BlockPos> reachableFrom(Set<BlockPos> facilities, double wingspan) {
        Map<BlockPos, Set<BlockPos>> links = new HashMap<>();
        for (Edge edge : edges) {
            if (edge.maxWingspan() < wingspan) {
                continue;
            }
            links.computeIfAbsent(edge.from(), ignored -> new HashSet<>()).add(edge.to());
            links.computeIfAbsent(edge.to(), ignored -> new HashSet<>()).add(edge.from());
        }
        Set<BlockPos> goals = new HashSet<>();
        for (Node node : nodes) {
            if ((node.kinds().contains(Kind.RUNWAY_ENTRY) || node.kinds().contains(Kind.HELIPAD))
                    && facilities.contains(node.facility())) {
                goals.add(node.pos());
            }
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.addAll(goals);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            if (!seen.add(pos)) {
                continue;
            }
            queue.addAll(links.getOrDefault(pos, Set.of()));
        }
        return Set.copyOf(seen);
    }
}
