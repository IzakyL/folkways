package io.github.izakyl.folkways.core.front.api.read;

import io.github.izakyl.folkways.core.colony.api.Colony;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class Railways {

    public static final ResourceLocation RAIL = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "rail");

    public record Station(UUID id, String name, ResourceLocation dimension, BlockPos at, UUID graph,
            boolean servedByColonyTrain) {

        public Station {
            at = at.immutable();
        }
    }

    public record Node(String id, UUID graph, Vec3 at, Optional<Vec3> outward) {
    }

    // Geometry stays inside the server until the caller clips it to its own read authorization.
    public record Edge(String id, UUID graph, boolean curve, List<Vec3> points) {

        public Edge {
            points = List.copyOf(points);
        }
    }

    public record Tracks(List<Node> nodes, List<Edge> edges) {

        public Tracks {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }
    }

    public interface Source {

        List<Station> stations(MinecraftServer server, Colony colony);

        Tracks tracks(ServerLevel level, Colony colony, AABB bounds);
    }

    private static final Map<ResourceLocation, Source> SOURCES = new LinkedHashMap<>();

    private Railways() {
    }

    public static void install(ResourceLocation id, Source source) {
        if (SOURCES.putIfAbsent(id, source) != null) {
            throw new IllegalArgumentException("Duplicate railway provider " + id);
        }
    }

    public static Optional<Source> source(ResourceLocation id) {
        return Optional.ofNullable(SOURCES.get(id));
    }
}
