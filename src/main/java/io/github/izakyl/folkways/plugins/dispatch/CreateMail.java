package io.github.izakyl.folkways.plugins.dispatch;

import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.packagePort.PackagePortTarget;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import io.github.izakyl.folkways.plugins.rail.domain.TransitNetwork;
import io.github.izakyl.folkways.plugins.rail.domain.TransitNetworks;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * How Create's trains carry the colony's parcels, as far as the colony relies on them: a postbox set to a station
 * sends from there, and a parcel goes only by a train that runs to a timetable the colony can read - every stop a
 * named station held only by delays, as for passengers - and calls at the sending station and later at the receiving
 * one. The colony chooses that train ahead of time, and the parcel waits in its postbox for that train alone (see
 * {@link MailGate}).
 */
final class CreateMail {

    // The train chosen for a parcel, and in how many ticks it would bring it to the receiving station.
    record Leg(UUID train, int arrives) {
    }

    private record Read(int tick, List<TransitNetwork.Stop> stops) {
    }

    // A train's timetable is reckoned once a tick at most, however many postboxes ask after it.
    private static final Map<UUID, Read> TIMETABLES = new ConcurrentHashMap<>();

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

    // The soonest one of the `trains` with room for goods brings a parcel from one station to the other: it calls at
    // `from` first and at `to` after, on the tracks both stand on.
    static Optional<Leg> leg(MinecraftServer server, GlobalStation from, GlobalStation to, Predicate<UUID> trains) {
        Optional<TrackGraph> graph = graphOf(server, from);
        if (graph.isEmpty() || graph.get().getPoint(EdgePointType.STATION, to.id) != to) {
            return Optional.empty();
        }
        TransitNetwork transit = TransitNetworks.get();
        Optional<Leg> best = Optional.empty();
        TIMETABLES.keySet().retainAll(railways(server).trains.keySet());
        for (Train train : railways(server).trains.values()) {
            if (train.graph != graph.get() || !trains.test(train.id) || !holdsGoods(train)
                    || !transit.isServiceable(server, train.id)) {
                continue;
            }
            int boarded = -1;
            for (TransitNetwork.Stop stop : timetable(server, transit, train.id)) {
                if (boarded < 0 && stop.station().equals(from.name)) {
                    boarded = stop.arrivesIn();
                } else if (boarded >= 0 && stop.station().equals(to.name)) {
                    if (best.isEmpty() || stop.arrivesIn() < best.get().arrives()) {
                        best = Optional.of(new Leg(train.id, stop.arrivesIn()));
                    }
                    break;
                }
            }
        }
        return best;
    }

    private static List<TransitNetwork.Stop> timetable(MinecraftServer server, TransitNetwork transit, UUID train) {
        int now = server.getTickCount();
        Read read = TIMETABLES.get(train);
        if (read == null || read.tick() != now) {
            read = new Read(now, transit.timetable(server, train));
            TIMETABLES.put(train, read);
        }
        return read.stops();
    }

    private static boolean holdsGoods(Train train) {
        for (Carriage carriage : train.carriages) {
            IItemHandlerModifiable cargo = carriage.storage.getAllItems();
            if (cargo != null && cargo.getSlots() > 0) {
                return true;
            }
        }
        return false;
    }

    // What the train is called, as its driver's seat reads it.
    static Optional<Component> nameOf(MinecraftServer server, UUID train) {
        return Optional.ofNullable(railways(server).trains.get(train)).<Component>map(found -> found.name);
    }

    static List<GlobalStation> stationsOf(ServerLevel level, Collection<BlockPos> senders) {
        List<GlobalStation> found = new ArrayList<>();
        for (BlockPos sender : senders) {
            stationOf(level, sender).ifPresent(found::add);
        }
        return found;
    }

    private static GlobalRailwayManager railways(MinecraftServer server) {
        return Create.RAILWAYS.sided(server.overworld());
    }
}
