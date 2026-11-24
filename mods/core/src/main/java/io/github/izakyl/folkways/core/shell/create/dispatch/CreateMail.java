package io.github.izakyl.folkways.core.shell.create.dispatch;

import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.packagePort.PackagePortTarget;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import io.github.izakyl.folkways.core.shell.create.rail.RailCargo;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitNetwork;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitNetworks;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Station and train readings for Create's postboxes. */
final class CreateMail {

    private CreateMail() {
    }

    static boolean posts(PackagePortBlockEntity port) {
        return port.target instanceof PackagePortTarget.TrainStationFrogportTarget;
    }

    // The station a postbox is set to.
    static Optional<GlobalStation> stationOf(ServerLevel level, BlockPos postbox) {
        return CreatePackageNetwork.portAt(level, postbox)
            .filter(CreateMail::posts)
            .map(port -> port.target.be(level, postbox))
            .filter(StationBlockEntity.class::isInstance)
            .map(found -> ((StationBlockEntity) found).getStation());
    }

    // Every postbox in this level set to a station on the same tracks as one of the senders' stations.
    static List<BlockPos> reached(ServerLevel level, Collection<BlockPos> senders) {
        Set<BlockPos> found = new LinkedHashSet<>();
        Set<TrackGraph> graphs = new LinkedHashSet<>();
        for (BlockPos sender : senders) {
            stationOf(level, sender).flatMap(station -> graphOf(level.getServer(), station)).ifPresent(graphs::add);
        }
        for (TrackGraph graph : graphs) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                if (level.dimension().equals(station.getBlockEntityDimension())) {
                    found.addAll(station.connectedPorts.keySet());
                }
            }
        }
        found.removeAll(senders);
        return List.copyOf(found);
    }

    static Optional<TrackGraph> graphOf(MinecraftServer server, GlobalStation station) {
        for (TrackGraph graph : railways(server).trackNetworks.values()) {
            if (graph.getPoint(EdgePointType.STATION, station.id) == station) {
                return Optional.of(graph);
            }
        }
        return Optional.empty();
    }

    static List<PackageNetwork.TrainArrival> trainsBetween(MinecraftServer server, GlobalStation from,
            GlobalStation to, Function<UUID, List<TransitNetwork.Stop>> timetable) {
        Optional<TrackGraph> graph = graphOf(server, from);
        if (from == to || graph.isEmpty() || graph.get().getPoint(EdgePointType.STATION, to.id) != to) {
            return List.of();
        }
        TransitNetwork transit = TransitNetworks.get();
        List<PackageNetwork.TrainArrival> arrivals = new ArrayList<>();
        for (Train train : railways(server).trains.values()) {
            if (train.graph != graph.get() || !transit.isServiceable(server, train.id)) {
                continue;
            }
            int boarded = -1;
            for (TransitNetwork.Stop stop : timetable.apply(train.id)) {
                if (boarded < 0 && stop.station().equals(from.name)) {
                    boarded = stop.arrivesIn();
                } else if (boarded >= 0 && stop.station().equals(to.name)) {
                    long storageCarriages = train.carriages.stream().filter(RailCargo::hasStorage).count();
                    arrivals.add(new PackageNetwork.TrainArrival(train.id, stop.arrivesIn(), storageCarriages));
                    break;
                }
            }
        }
        return List.copyOf(arrivals);
    }

    // What the train is called, as its driver's seat reads it.
    static Optional<Component> nameOf(MinecraftServer server, UUID train) {
        return Optional.ofNullable(railways(server).trains.get(train)).<Component>map(found -> found.name);
    }

    private static GlobalRailwayManager railways(MinecraftServer server) {
        return Create.RAILWAYS.sided(server.overworld());
    }
}
