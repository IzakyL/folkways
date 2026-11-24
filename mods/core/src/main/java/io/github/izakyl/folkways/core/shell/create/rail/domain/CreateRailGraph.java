package io.github.izakyl.folkways.core.shell.create.rail.domain;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.EdgePointStorage;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;
import io.github.izakyl.folkways.core.front.api.read.Railways;
import io.github.izakyl.folkways.core.script.api.Workers;
import io.github.izakyl.folkways.core.shell.create.rail.domain.mixin.RailGraphAccessor;
import io.github.izakyl.folkways.core.shell.create.rail.domain.mixin.RailPointsAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class CreateRailGraph {
    private CreateRailGraph() {
    }

    public static boolean available() {
        return RailGraphAccessor.class.isAssignableFrom(TrackGraph.class)
                && RailPointsAccessor.class.isAssignableFrom(EdgePointStorage.class);
    }

    public static List<Railways.Station> stations(MinecraftServer server, Predicate<UUID> held) {
        var railways = Create.RAILWAYS.sided(server.overworld());
        Map<UUID, List<Pattern>> services = new HashMap<>();
        for (var train : railways.trains.values()) {
            Workers.checkTime();
            if (train.graph == null || !held.test(train.id)
                    || train.runtime.getSchedule() == null) {
                continue;
            }
            for (var entry : train.runtime.getSchedule().entries) {
                if (entry.instruction instanceof DestinationInstruction destination) {
                    services.computeIfAbsent(train.graph.id, ignored -> new ArrayList<>())
                            .add(Pattern.compile(destination.getFilterForRegex()));
                }
            }
        }
        List<Railways.Station> stations = new ArrayList<>();
        for (TrackGraph graph : railways.trackNetworks.values()) {
            var destinations = services.getOrDefault(graph.id, List.of());
            // Create's getPoints creates a persistent empty map on a cold graph.
            var storage = ((RailGraphAccessor) graph).folkways$edgePoints();
            var points = ((RailPointsAccessor) storage).folkways$pointsByType();
            for (var point : points.getOrDefault(EdgePointType.STATION, Map.of()).values()) {
                GlobalStation station = (GlobalStation) point;
                Workers.checkTime();
                stations.add(new Railways.Station(station.id, station.name,
                        station.getBlockEntityDimension().location(), station.getBlockEntityPos(), graph.id,
                        destinations.stream().anyMatch(pattern -> pattern.matcher(station.name).matches())));
            }
        }
        stations.sort(Comparator.comparing(station -> station.graph() + "/" + station.id()));
        return List.copyOf(stations);
    }

    public static Railways.Tracks tracks(ServerLevel level, AABB bounds) {
        List<Railways.Node> nodes = new ArrayList<>();
        List<Railways.Edge> edges = new ArrayList<>();
        for (TrackGraph graph : Create.RAILWAYS.sided(level).trackNetworks.values()) {
            for (var location : graph.getNodes()) {
                Workers.checkTime();
                if (!location.getDimension().equals(level.dimension())) {
                    continue;
                }
                TrackNode node = graph.locateNode(location);
                var connections = graph.getConnectionsFrom(node);
                if (bounds.contains(location.getLocation())) {
                    Optional<Vec3> outward = Optional.empty();
                    if (connections.size() == 1) {
                        TrackEdge edge = connections.values().iterator().next();
                        if (!edge.isInterDimensional()) {
                            outward = Optional.of((edge.isTurn() ? edge.getTurn().axes.getFirst()
                                    : edge.getDirection(true)).normalize().scale(-1));
                        }
                    }
                    nodes.add(new Railways.Node(id(graph, node), graph.id, location.getLocation(), outward));
                }
                for (var connection : connections.entrySet()) {
                    TrackEdge edge = connection.getValue();
                    if (edge.isInterDimensional() || node.getNetId() >= connection.getKey().getNetId()) {
                        continue;
                    }
                    Vec3 from = edge.getPosition(graph, 0);
                    Vec3 to = edge.getPosition(graph, 1);
                    AABB extent = edge.isTurn() ? edge.getTurn().getBounds() : new AABB(from, to);
                    if (!extent.inflate(0.001).intersects(bounds)) {
                        continue;
                    }
                    int steps = edge.isTurn() ? Math.max(1, (int) Math.ceil(edge.getLength() * 2)) : 1;
                    List<Vec3> points = new ArrayList<>();
                    for (int step = 0; step <= steps; step++) {
                        Workers.checkTime();
                        points.add(edge.getPosition(graph, (double) step / steps));
                    }
                    edges.add(new Railways.Edge(id(graph, node) + "/" + connection.getKey().getNetId(),
                            graph.id, edge.isTurn(), points));
                }
            }
        }
        nodes.sort(Comparator.comparing(Railways.Node::id));
        edges.sort(Comparator.comparing(Railways.Edge::id));
        return new Railways.Tracks(nodes, edges);
    }

    private static String id(TrackGraph graph, TrackNode node) {
        return graph.id + "/" + node.getNetId();
    }
}
