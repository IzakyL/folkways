package io.github.izakyl.folkways.vanilla.create.dispatch;

import io.github.izakyl.folkways.core.shell.create.dispatch.CreateLayout;
import io.github.izakyl.folkways.core.shell.create.dispatch.PackageNetwork;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitNetwork;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitNetworks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

final class DispatchRoutes {

    record Flaw(NetworkFlaw kind, BlockPos at) {
    }

    record Route(UUID train, String station, int arrives) {
    }

    private static final Map<UUID, List<TransitNetwork.Stop>> TIMETABLES = new HashMap<>();
    private static int timetableTick = -1;

    private DispatchRoutes() {
    }

    static Optional<Flaw> flawOf(PackageNetwork network, ServerLevel level, UUID id) {
        return network.layoutOf(level, id).flatMap(layout -> flawOf(network, level, layout));
    }

    private static Optional<Flaw> flawOf(PackageNetwork network, ServerLevel level, CreateLayout layout) {
        for (GlobalPos link : layout.loadedLinks()) {
            if (!level.dimension().equals(link.dimension())) {
                return Optional.of(new Flaw(NetworkFlaw.ELSEWHERE, link.pos()));
            }
        }
        if (layout.packagers().isEmpty()) {
            return Optional.of(new Flaw(NetworkFlaw.NO_PACKAGER, BlockPos.ZERO));
        }
        for (BlockPos packager : layout.packagers()) {
            List<CreateLayout.Port> ports = layout.adjacentPorts().get(packager);
            for (CreateLayout.Port port : ports) {
                if (!port.postbox() && port.chain().isEmpty()) {
                    return Optional.of(new Flaw(NetworkFlaw.OFF_CHAIN, port.at()));
                }
            }
            if (ports.isEmpty()) {
                return Optional.of(new Flaw(NetworkFlaw.NO_FROGPORT, packager));
            }
        }
        if (!layout.posts().isEmpty() && !layout.chainSeeds().isEmpty()) {
            return Optional.of(new Flaw(NetworkFlaw.MIXED, layout.posts().values().iterator().next()));
        }
        if (!layout.unloadedChains().isEmpty()) {
            return Optional.of(new Flaw(NetworkFlaw.UNLOADED, layout.unloadedChains().getFirst()));
        }
        for (BlockPos seed : layout.chainSeeds()) {
            if (!layout.chains().contains(seed)) {
                return Optional.of(new Flaw(NetworkFlaw.SPLIT, seed));
            }
        }
        for (BlockPos chain : layout.chains()) {
            if (network.chainSpeed(level, chain) == 0.0D) {
                return Optional.of(new Flaw(NetworkFlaw.STILL, chain));
            }
        }
        return Optional.empty();
    }

    private static boolean mails(CreateLayout layout) {
        return !layout.posts().isEmpty() && layout.chains().isEmpty();
    }

    private static Optional<CreateLayout> flawless(PackageNetwork network, ServerLevel level, UUID id) {
        return network.layoutOf(level, id).filter(layout -> flawOf(network, level, layout).isEmpty());
    }

    static Map<BlockPos, BlockPos> postsOf(PackageNetwork network, ServerLevel level, UUID id) {
        return flawless(network, level, id).filter(DispatchRoutes::mails).map(CreateLayout::posts).orElseGet(Map::of);
    }

    static void post(PackageNetwork network, ServerLevel level, UUID id, IntPredicate ours) {
        postsOf(network, level, id).forEach((packager, postbox) -> network.post(level, packager, postbox, ours));
    }

    static List<BlockPos> portsOf(PackageNetwork network, ServerLevel level, UUID id, Predicate<UUID> trains) {
        Optional<CreateLayout> found = flawless(network, level, id);
        if (found.isEmpty()) {
            return List.of();
        }
        CreateLayout layout = found.get();
        if (mails(layout)) {
            return network.railPortsReached(level, layout.posts().values()).stream()
                .filter(post -> mailTicks(network, level, layout, post, trains).isPresent()).toList();
        }
        return network.portsOn(level, layout.chains()).stream()
            .filter(port -> !layout.senders().contains(port) && !network.unpacks(level, port)).toList();
    }

    static OptionalInt transitTicks(PackageNetwork network, ServerLevel level, UUID id, BlockPos port,
            Predicate<UUID> trains) {
        Optional<CreateLayout> found = flawless(network, level, id);
        if (found.isEmpty()) {
            return OptionalInt.empty();
        }
        CreateLayout layout = found.get();
        OptionalInt travel;
        if (mails(layout)) {
            travel = mailTicks(network, level, layout, port, trains);
        } else {
            Optional<BlockPos> target = network.chainOf(level, port).filter(layout.chains()::contains);
            if (target.isEmpty()) {
                return OptionalInt.empty();
            }
            List<BlockPos> starts = new ArrayList<>();
            for (BlockPos sender : layout.senders()) {
                network.chainOf(level, sender).ifPresent(starts::add);
            }
            travel = network.chainTicks(level, starts, target.get());
        }
        return travel.isEmpty() ? travel : OptionalInt.of(network.packingTicks(level, layout) + travel.getAsInt());
    }

    // Any packager may fill the order, so use the slowest sending station's arrival.
    private static OptionalInt mailTicks(PackageNetwork network, ServerLevel level, CreateLayout layout, BlockPos to,
            Predicate<UUID> trains) {
        if (network.stationOf(level, to).isEmpty()) {
            return OptionalInt.empty();
        }
        int slowest = 0;
        for (BlockPos from : layout.posts().values()) {
            if (network.stationOf(level, from).isEmpty()) {
                continue;
            }
            Optional<Route> leg = route(network, level, from, to, trains);
            if (leg.isEmpty()) {
                return OptionalInt.empty();
            }
            slowest = Math.max(slowest, leg.get().arrives());
        }
        return OptionalInt.of(slowest);
    }

    static List<BlockPos> reachedFrom(PackageNetwork network, ServerLevel level, BlockPos from,
            Predicate<UUID> trains) {
        if (network.mails(level, from)) {
            return network.railPortsReached(level, List.of(from)).stream()
                .filter(to -> route(network, level, from, to, trains).isPresent()).toList();
        }
        return network.chainOf(level, from).stream()
            .flatMap(chain -> network.portsOn(level, network.chainNet(level, chain)).stream())
            .filter(to -> !to.equals(from) && !network.unpacks(level, to)).toList();
    }

    static OptionalInt handTicks(PackageNetwork network, ServerLevel level, BlockPos from, BlockPos to,
            Predicate<UUID> trains) {
        if (!network.mails(level, from)) {
            return network.throwTicks(level, from, to);
        }
        return route(network, level, from, to, trains).map(route -> OptionalInt.of(route.arrives()))
            .orElseGet(OptionalInt::empty);
    }

    static Optional<Route> route(PackageNetwork network, ServerLevel level, BlockPos from, BlockPos to,
            Predicate<UUID> trains) {
        Optional<String> station = network.stationOf(level, from);
        if (station.isEmpty()) {
            return Optional.empty();
        }
        PackageNetwork.TrainArrival best = null;
        for (PackageNetwork.TrainArrival arrival : network.trainsBetween(level, from, to,
                train -> timetable(level.getServer(), train))) {
            if (trains.test(arrival.train()) && arrival.storageCarriages() > 0
                    && (best == null || arrival.arrives() < best.arrives())) {
                best = arrival;
            }
        }
        return best == null ? Optional.empty() : Optional.of(new Route(best.train(), station.get(), best.arrives()));
    }

    private static List<TransitNetwork.Stop> timetable(MinecraftServer server, UUID train) {
        int tick = server.getTickCount();
        if (timetableTick != tick) {
            TIMETABLES.clear();
            timetableTick = tick;
        }
        return TIMETABLES.computeIfAbsent(train, id -> TransitNetworks.get().timetable(server, id));
    }

    static void forgetServer() {
        TIMETABLES.clear();
        timetableTick = -1;
    }
}
