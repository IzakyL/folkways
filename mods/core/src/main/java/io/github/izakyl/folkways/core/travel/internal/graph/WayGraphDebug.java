package io.github.izakyl.folkways.core.travel.internal.graph;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Bounded, detached diagnostics of published ways. No queries, caches or searches are advanced here. */
public final class WayGraphDebug {
    public static final int RADIUS = 48;
    public static final int MAX_POINTS = 2000;
    public static final int MAX_EDGES = 4000;
    // Crossings sit outside both caps; this bounds only what one packet may carry of them.
    public static final int MAX_CROSSINGS = 1024;
    public static final int WIRE_POINTS = MAX_POINTS + 2 * MAX_CROSSINGS;
    public static final int WIRE_EDGES = MAX_EDGES + MAX_CROSSINGS;

    public enum PointKind {
        WAYPOINT, FOOT, CROSSING
    }

    public enum EdgeKind {
        NORMAL, PROBATION, STRUCK, STRUCK_PROBATION, CROSSING
    }

    public record Point(BlockPos pos, PointKind kind) {
    }

    // sort is the locomotion id for a net link and the passage id for a crossing. Links are directed.
    public record Edge(int from, int to, EdgeKind kind, ResourceLocation sort) {
    }

    public record Snapshot(List<Point> points, List<Edge> edges, boolean truncated) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), false);

        public Snapshot {
            points = List.copyOf(points);
            edges = List.copyOf(edges);
        }
    }

    private WayGraphDebug() {
    }

    public static Snapshot read(ServerLevel level, WayGraph graph, Vec3 centre) {
        return read(level, graph, centre, RADIUS, MAX_POINTS, MAX_EDGES);
    }

    public static Snapshot read(ServerLevel level, WayGraph graph, Vec3 centre, int radius,
            int maxPoints, int maxEdges) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("waypoint diagnostics require the server thread");
        }
        Reader reader = new Reader(level, centre, Math.clamp(radius, 0, RADIUS),
            Math.clamp(maxPoints, 0, MAX_POINTS), Math.clamp(maxEdges, 0, MAX_EDGES));
        // Crossings belong to their owners, not to the nets: they are read whole, outside every cap, so no busy
        // walking net can crowd them out. Coincident kinds merge.
        int crossings = 0;
        for (Crossing crossing : graph.debugCrossings()) {
            if (crossings >= MAX_CROSSINGS) {
                reader.truncated = true;
                break;
            }
            int from = reader.point(crossing.boarding(), PointKind.CROSSING);
            int to = reader.point(crossing.landing(), PointKind.CROSSING);
            // Half-visible crossings still consume points even though they have no edge.
            if (from >= 0 || to >= 0) {
                crossings++;
            }
            if (from >= 0 && to >= 0) {
                reader.edges.add(new Edge(from, to, EdgeKind.CROSSING, crossing.passage()));
            }
        }
        reader.crossingEdges = reader.edges.size();
        for (List<WorldPos> feet : graph.debugFeet().values()) {
            for (WorldPos foot : feet) {
                reader.point(foot, PointKind.FOOT);
            }
        }
        for (var held : graph.debugNets().entrySet()) {
            Tract tract = held.getKey();
            Waypoints.Net net = held.getValue();
            Map<Integer, Integer> indices = new HashMap<>();
            for (var cell : net.byCell().long2IntEntrySet()) {
                int index = reader.point(new WorldPos(tract.realm(), BlockPos.of(cell.getLongKey())),
                    PointKind.WAYPOINT);
                if (index >= 0) {
                    indices.put(cell.getIntValue(), index);
                }
            }
            for (Waypoints.DebugLink link : net.debug()) {
                Integer from = indices.get(link.from());
                Integer to = indices.get(link.to());
                if (from == null || to == null) {
                    continue;
                }
                boolean probation = link.liveAt() > level.getGameTime();
                EdgeKind kind = link.strikes() > 0
                    ? probation ? EdgeKind.STRUCK_PROBATION : EdgeKind.STRUCK
                    : probation ? EdgeKind.PROBATION : EdgeKind.NORMAL;
                reader.edge(from, to, kind, tract.sort().locomotion());
            }
        }
        return new Snapshot(reader.points, reader.edges, reader.truncated);
    }

    private static final class Reader {
        final ServerLevel level;
        final Vec3 centre;
        final int radius;
        final int maxPoints;
        final int maxEdges;
        final List<Point> points = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>();
        final Map<WorldPos, Integer> indices = new LinkedHashMap<>();
        boolean truncated;
        int crossingPoints;
        int crossingEdges;

        Reader(ServerLevel level, Vec3 centre, int radius, int maxPoints, int maxEdges) {
            this.level = level;
            this.centre = centre;
            this.radius = radius;
            this.maxPoints = maxPoints;
            this.maxEdges = maxEdges;
        }

        int point(WorldPos at, PointKind kind) {
            Integer previous = indices.get(at);
            if (previous != null) {
                Point point = points.get(previous);
                if (kind.ordinal() > point.kind().ordinal()) {
                    points.set(previous, new Point(point.pos(), kind));
                }
                return previous;
            }
            var world = WorldSpaces.world(level, at);
            var storage = WorldSpaces.storage(level, at);
            if (world.isEmpty() || storage.isEmpty() || world.get().distanceToSqr(centre) > (double) radius * radius) {
                return -1;
            }
            if (kind != PointKind.CROSSING && points.size() - crossingPoints >= maxPoints) {
                truncated = true;
                return -1;
            }
            int index = points.size();
            if (kind == PointKind.CROSSING) {
                crossingPoints++;
            }
            points.add(new Point(storage.get().immutable(), kind));
            indices.put(at, index);
            return index;
        }

        void edge(int from, int to, EdgeKind kind, ResourceLocation sort) {
            if (edges.size() - crossingEdges >= maxEdges) {
                truncated = true;
                return;
            }
            edges.add(new Edge(from, to, kind, sort));
        }
    }
}
