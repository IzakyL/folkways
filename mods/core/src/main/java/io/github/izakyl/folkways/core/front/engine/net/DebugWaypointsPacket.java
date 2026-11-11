package io.github.izakyl.folkways.core.front.engine.net;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug.Edge;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug.EdgeKind;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug.Point;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug.PointKind;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug.Snapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public record DebugWaypointsPacket(UUID colony, ResourceKey<Level> dimension, Snapshot graph)
        implements CustomPacketPayload {
    public static final Type<DebugWaypointsPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysCoreMod.MOD_ID, "debug_waypoints"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DebugWaypointsPacket> STREAM_CODEC =
        StreamCodec.of(DebugWaypointsPacket::write, DebugWaypointsPacket::read);

    public DebugWaypointsPacket {
        if (graph.points().size() > WayGraphDebug.WIRE_POINTS || graph.edges().size() > WayGraphDebug.WIRE_EDGES) {
            throw new IllegalArgumentException("waypoint snapshot exceeds packet limits");
        }
        for (Edge edge : graph.edges()) {
            if (edge.from() < 0 || edge.to() < 0
                    || edge.from() >= graph.points().size() || edge.to() >= graph.points().size()) {
                throw new IllegalArgumentException("waypoint edge has no endpoint");
            }
        }
    }

    private static void write(RegistryFriendlyByteBuf buffer, DebugWaypointsPacket packet) {
        buffer.writeUUID(packet.colony());
        buffer.writeResourceKey(packet.dimension());
        Snapshot graph = packet.graph();
        buffer.writeBoolean(graph.truncated());
        buffer.writeVarInt(graph.points().size());
        for (Point point : graph.points()) {
            buffer.writeBlockPos(point.pos());
            buffer.writeEnum(point.kind());
        }
        // Intern locomotion/passage ids once; all endpoints and ids are then small varints.
        var sorts = new LinkedHashMap<ResourceLocation, Integer>();
        for (Edge edge : graph.edges()) {
            sorts.computeIfAbsent(edge.sort(), key -> sorts.size());
        }
        buffer.writeVarInt(sorts.size());
        for (ResourceLocation sort : sorts.keySet()) {
            buffer.writeResourceLocation(sort);
        }
        buffer.writeVarInt(graph.edges().size());
        for (Edge edge : graph.edges()) {
            buffer.writeVarInt(edge.from());
            buffer.writeVarInt(edge.to());
            buffer.writeEnum(edge.kind());
            buffer.writeVarInt(sorts.get(edge.sort()));
        }
    }

    private static DebugWaypointsPacket read(RegistryFriendlyByteBuf buffer) {
        UUID colony = buffer.readUUID();
        ResourceKey<Level> dimension = buffer.readResourceKey(Registries.DIMENSION);
        boolean truncated = buffer.readBoolean();
        int size = count(buffer, WayGraphDebug.WIRE_POINTS);
        var points = new ArrayList<Point>(size);
        for (int index = 0; index < size; index++) {
            points.add(new Point(buffer.readBlockPos(), buffer.readEnum(PointKind.class)));
        }
        size = count(buffer, WayGraphDebug.WIRE_EDGES);
        var sorts = new ArrayList<ResourceLocation>(size);
        for (int index = 0; index < size; index++) {
            sorts.add(buffer.readResourceLocation());
        }
        size = count(buffer, WayGraphDebug.WIRE_EDGES);
        var edges = new ArrayList<Edge>(size);
        for (int index = 0; index < size; index++) {
            int from = buffer.readVarInt();
            int to = buffer.readVarInt();
            EdgeKind kind = buffer.readEnum(EdgeKind.class);
            int sort = buffer.readVarInt();
            if (sort < 0 || sort >= sorts.size()) {
                throw new IllegalArgumentException("unknown waypoint sort");
            }
            edges.add(new Edge(from, to, kind, sorts.get(sort)));
        }
        return new DebugWaypointsPacket(colony, dimension, new Snapshot(points, edges, truncated));
    }

    private static int count(RegistryFriendlyByteBuf buffer, int maximum) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException("waypoint collection exceeds packet limits");
        }
        return count;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
