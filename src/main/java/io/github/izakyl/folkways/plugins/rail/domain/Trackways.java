package io.github.izakyl.folkways.plugins.rail.domain;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import io.github.izakyl.folkways.core.api.terms.Keepouts;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

// Nobody walks along or across a railway: every track, as wide as the widest train that runs on it and as tall as a
// carriage, is ground a path goes around. It is traced again whenever the track or the trains on it change.
public final class Trackways implements Keepouts.Keepout {

    private static final double RAILS = 0.5D;

    private static final double BODY = 0.3D;

    private static final int BELOW = 1;

    private static final int ABOVE = 4;

    private static final double STEP = 0.5D;

    private volatile Map<ResourceKey<Level>, LongSet> cells = Map.of();

    private long stamp = Long.MIN_VALUE;

    private int checkedAt = -1;

    @Override
    public boolean forbids(Level level, int x, int y, int z) {
        refresh(level.getServer());
        LongSet here = cells.get(level.dimension());
        return here != null && here.contains(BlockPos.asLong(x, y, z));
    }

    private void refresh(MinecraftServer server) {
        if (server == null || !server.isSameThread() || server.getTickCount() == checkedAt) {
            return;
        }
        checkedAt = server.getTickCount();
        GlobalRailwayManager railways = Create.RAILWAYS.sided(server.overworld());
        Map<UUID, Double> widest = widest(railways);
        long now = 31L * widest.hashCode();
        for (Map.Entry<UUID, TrackGraph> graph : railways.trackNetworks.entrySet()) {
            now = 31L * now + graph.getKey().hashCode() * 17L + graph.getValue().getChecksum();
        }
        if (now == stamp) {
            return;
        }
        stamp = now;
        Map<ResourceKey<Level>, LongSet> traced = new HashMap<>();
        for (Map.Entry<UUID, TrackGraph> graph : railways.trackNetworks.entrySet()) {
            trace(graph.getValue(), widest.getOrDefault(graph.getKey(), 0.0D), traced);
        }
        cells = Map.copyOf(traced);
    }

    private static Map<UUID, Double> widest(GlobalRailwayManager railways) {
        Map<UUID, Double> widest = new HashMap<>();
        for (Train train : railways.trains.values()) {
            if (train.graph == null) {
                continue;
            }
            for (Carriage carriage : train.carriages) {
                CarriageContraptionEntity entity = carriage.anyAvailableEntity();
                if (entity != null) {
                    widest.merge(train.graph.id, CreateTransitNetwork.extent(entity)[0] / 2.0D, Math::max);
                }
            }
        }
        return widest;
    }

    private static void trace(TrackGraph graph, double halfWidth, Map<ResourceKey<Level>, LongSet> traced) {
        double reach = Math.max(RAILS, halfWidth) + BODY;
        for (TrackNodeLocation location : graph.getNodes()) {
            TrackNode node = graph.locateNode(location);
            if (node == null) {
                continue;
            }
            LongSet into = traced.computeIfAbsent(location.getDimension(), dimension -> new LongOpenHashSet());
            for (Map.Entry<TrackNode, TrackEdge> edge : graph.getConnectionsFrom(node).entrySet()) {
                if (edge.getValue().isInterDimensional() || edge.getKey().getNetId() < node.getNetId()) {
                    continue;
                }
                int steps = Math.max(1, Mth.ceil(edge.getValue().getLength() / STEP));
                for (int step = 0; step <= steps; step++) {
                    mark(into, edge.getValue().getPosition(graph, step / (double) steps), reach);
                }
            }
        }
    }

    private static void mark(LongSet into, Vec3 at, double reach) {
        int low = Mth.floor(at.y) - BELOW;
        int high = Mth.floor(at.y) + ABOVE;
        for (int x = Mth.floor(at.x - reach); x <= Mth.floor(at.x + reach); x++) {
            for (int z = Mth.floor(at.z - reach); z <= Mth.floor(at.z + reach); z++) {
                double dx = x + 0.5D - at.x;
                double dz = z + 0.5D - at.z;
                if (dx * dx + dz * dz >= reach * reach) {
                    continue;
                }
                for (int y = low; y <= high; y++) {
                    into.add(BlockPos.asLong(x, y, z));
                }
            }
        }
    }
}
