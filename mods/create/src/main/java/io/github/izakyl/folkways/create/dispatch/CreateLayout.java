package io.github.izakyl.folkways.create.dispatch;

import com.simibubi.create.Create;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.packagePort.PackagePortTarget;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticsNetwork;
import com.simibubi.create.content.trains.station.GlobalStation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * How a Create package network ships, as far as the colony relies on it: every packager on the network hands its
 * parcels to frogports beside it, and those frogports all sit on one chain net that is loaded and turning; or every
 * packager has a postbox beside it set to a station, and the colony sends what it packs on by train. A network
 * shaped otherwise is still read, so a click on it is understood, but it carries a flaw and nothing is ordered from it.
 * `posts` holds each mailing packager's postbox.
 */
record CreateLayout(List<BlockPos> packagers, List<BlockPos> containers, Set<BlockPos> senders,
                    Set<BlockPos> chains, Map<BlockPos, BlockPos> posts, Optional<PackageNetwork.Flaw> flaw) {

    private static final int MOST_CHAINS = 1024;
    // A parcel rounds half of each conveyor it passes, on Create's loop of radius 1.5.
    private static final double HALF_LOOP = Math.PI * 1.5D;
    // Create moves a chain's parcels |rpm| / 360 * 4 blocks a tick (ChainConveyorBlockEntity.tick).
    private static final double DEGREES = 360.0D;
    private static final double PARCEL_SPEED = 4.0D;
    // A frogport's throw onto the chain, and the catch at the far end.
    private static final int FROG_TICKS = 20;

    static CreateLayout of(ServerLevel level, UUID network) {
        Flaws flaws = new Flaws();
        LogisticsNetwork wired = Create.LOGISTICS.logisticsNetworks.get(network);
        List<BlockPos> packagers = new ArrayList<>();
        if (wired != null) {
            for (GlobalPos link : wired.loadedLinks) {
                if (!link.dimension().equals(level.dimension())) {
                    flaws.note(NetworkFlaw.ELSEWHERE, link.pos());
                } else if (level.isLoaded(link.pos())) {
                    packagers.addAll(packagersBeside(level, link.pos()));
                }
            }
        }
        if (packagers.isEmpty()) {
            flaws.note(NetworkFlaw.NO_PACKAGER, BlockPos.ZERO);
        }
        Set<BlockPos> senders = new LinkedHashSet<>();
        List<BlockPos> seeds = new ArrayList<>();
        Map<BlockPos, BlockPos> posts = new LinkedHashMap<>();
        for (BlockPos packager : packagers) {
            boolean ships = false;
            for (Direction side : Direction.values()) {
                Optional<PackagePortBlockEntity> port = CreatePackageNetwork.portAt(level, packager.relative(side));
                if (port.isEmpty()) {
                    continue;
                }
                if (CreateMail.posts(port.get())) {
                    posts.putIfAbsent(packager, port.get().getBlockPos());
                    senders.add(port.get().getBlockPos());
                    ships = true;
                    continue;
                }
                Optional<BlockPos> chain = chainOf(port.get());
                if (chain.isEmpty()) {
                    // It would pull the packager's parcels and hold them with nowhere to throw them.
                    flaws.note(NetworkFlaw.OFF_CHAIN, port.get().getBlockPos());
                    continue;
                }
                senders.add(port.get().getBlockPos());
                seeds.add(chain.get());
                ships = true;
            }
            if (!ships) {
                flaws.note(NetworkFlaw.NO_FROGPORT, packager);
            }
        }
        if (!posts.isEmpty() && !seeds.isEmpty()) {
            // Create packs an order at whichever packager it likes, so every one of them must send the same way.
            flaws.note(NetworkFlaw.MIXED, posts.values().iterator().next());
        }
        Set<BlockPos> chains = new LinkedHashSet<>();
        if (!seeds.isEmpty()) {
            chains.addAll(walk(level, seeds.getFirst(), flaws));
            seeds.stream().filter(seed -> !chains.contains(seed)).findFirst()
                .ifPresent(apart -> flaws.note(NetworkFlaw.SPLIT, apart));
            for (BlockPos at : chains) {
                if (level.getBlockEntity(at) instanceof ChainConveyorBlockEntity chain && speedOf(chain) == 0.0D) {
                    flaws.note(NetworkFlaw.STILL, at);
                    break;
                }
            }
        }
        Set<BlockPos> containers = new LinkedHashSet<>();
        for (BlockPos packager : packagers) {
            targetOf(level, packager).ifPresent(containers::add);
        }
        return new CreateLayout(List.copyOf(packagers), List.copyOf(containers), senders, chains, Map.copyOf(posts),
            flaws.first);
    }

    static Optional<BlockPos> chainOf(PackagePortBlockEntity port) {
        return port.target instanceof PackagePortTarget.ChainConveyorFrogportTarget onChain
            && onChain.relativePos != null
            ? Optional.of(port.getBlockPos().offset(onChain.relativePos))
            : Optional.empty();
    }

    static List<BlockPos> packagersBeside(ServerLevel level, BlockPos pos) {
        List<BlockPos> found = new ArrayList<>();
        for (Direction side : Direction.values()) {
            BlockPos beside = pos.relative(side);
            if (level.isLoaded(beside) && level.getBlockEntity(beside) instanceof PackagerBlockEntity) {
                found.add(beside);
            }
        }
        return found;
    }

    // The container a packager packs from and unpacks into: the block behind the way it faces, if that holds items.
    static Optional<BlockPos> targetOf(ServerLevel level, BlockPos packager) {
        Direction facing = level.getBlockState(packager).getOptionalValue(PackagerBlock.FACING).orElse(Direction.UP);
        BlockPos target = packager.relative(facing.getOpposite());
        if (!level.isLoaded(target) || level.getBlockEntity(target) instanceof PackagerBlockEntity
                || level.getCapability(Capabilities.ItemHandler.BLOCK, target, facing) == null) {
            return Optional.empty();
        }
        return Optional.of(target.immutable());
    }

    // Whether a port hands on what it catches: a frogport pushes its parcels into whatever holds items below it, and a
    // packager there unpacks them into the container it faces. A postbox keeps what it is sent.
    static boolean unpacks(ServerLevel level, BlockPos port) {
        if (!(level.getBlockEntity(port) instanceof FrogportBlockEntity)) {
            return false;
        }
        BlockPos below = port.below();
        if (!level.isLoaded(below)) {
            return false;
        }
        BlockEntity under = level.getBlockEntity(below);
        if (under == null || under instanceof FrogportBlockEntity
                || level.getCapability(Capabilities.ItemHandler.BLOCK, below, Direction.UP) == null) {
            return false;
        }
        return !(under instanceof PackagerBlockEntity) || targetOf(level, below).isPresent();
    }

    boolean flawless() {
        return flaw.isEmpty();
    }

    // Whether the network sends what it packs by train.
    boolean mails() {
        return !posts.isEmpty() && chains.isEmpty();
    }

    // Create keys a chain's ports by where each port stands from the chain; the senders are where parcels set out.
    // A network that mails reaches every postbox one of the `trains` runs to from each of its stations.
    List<BlockPos> ports(ServerLevel level, Predicate<UUID> trains) {
        if (mails()) {
            List<BlockPos> found = new ArrayList<>();
            for (BlockPos post : CreateMail.reached(level, posts.values())) {
                if (mailTicks(level, post, trains).isPresent()) {
                    found.add(post);
                }
            }
            return List.copyOf(found);
        }
        Set<BlockPos> found = new LinkedHashSet<>();
        for (BlockPos at : chains) {
            if (level.getBlockEntity(at) instanceof ChainConveyorBlockEntity chain) {
                chain.loopPorts.keySet().forEach(offset -> found.add(at.offset(offset)));
                chain.travelPorts.keySet().forEach(offset -> found.add(at.offset(offset)));
            }
        }
        found.removeAll(senders);
        found.removeIf(port -> CreatePackageNetwork.portAt(level, port).isEmpty() || unpacks(level, port));
        return List.copyOf(found);
    }

    /**
     * From an order to its parcels at this port: the busiest packager's queue, the throw, the shortest ride along the
     * chains at each conveyor's own speed, and the catch.
     */
    OptionalInt transitTicks(ServerLevel level, BlockPos port, Predicate<UUID> trains) {
        if (!flawless()) {
            return OptionalInt.empty();
        }
        if (mails()) {
            OptionalInt train = mailTicks(level, port, trains);
            return train.isEmpty() ? train : OptionalInt.of(packing(level) + train.getAsInt());
        }
        Optional<BlockPos> target = CreatePackageNetwork.portAt(level, port).flatMap(CreateLayout::chainOf)
            .filter(chains::contains);
        if (target.isEmpty()) {
            return OptionalInt.empty();
        }
        List<BlockPos> starts = new ArrayList<>();
        for (BlockPos sender : senders) {
            CreatePackageNetwork.portAt(level, sender).flatMap(CreateLayout::chainOf).ifPresent(starts::add);
        }
        double ride = ride(level, starts, target.get());
        if (Double.isInfinite(ride)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(packing(level) + 2 * FROG_TICKS + (int) Math.ceil(ride));
    }

    // Every port a parcel thrown onto the chains at this frogport could be caught at, less the ones that would hand it
    // on into a container.
    static List<BlockPos> portsAlong(ServerLevel level, BlockPos frogport) {
        Optional<BlockPos> start = CreatePackageNetwork.portAt(level, frogport).flatMap(CreateLayout::chainOf);
        if (start.isEmpty()) {
            return List.of();
        }
        Set<BlockPos> found = new LinkedHashSet<>();
        for (BlockPos at : walk(level, start.get(), new Flaws())) {
            if (level.getBlockEntity(at) instanceof ChainConveyorBlockEntity chain) {
                chain.loopPorts.keySet().forEach(offset -> found.add(at.offset(offset)));
                chain.travelPorts.keySet().forEach(offset -> found.add(at.offset(offset)));
            }
        }
        found.remove(frogport);
        found.removeIf(port -> CreatePackageNetwork.portAt(level, port).isEmpty() || unpacks(level, port));
        return List.copyOf(found);
    }

    // The throw at one frogport, the ride along the chains to another, and the catch there.
    static OptionalInt throwTicks(ServerLevel level, BlockPos from, BlockPos to) {
        Optional<BlockPos> start = CreatePackageNetwork.portAt(level, from).flatMap(CreateLayout::chainOf);
        Optional<BlockPos> end = CreatePackageNetwork.portAt(level, to).flatMap(CreateLayout::chainOf);
        if (start.isEmpty() || end.isEmpty()) {
            return OptionalInt.empty();
        }
        double ride = ride(level, List.of(start.get()), end.get());
        return Double.isInfinite(ride) ? OptionalInt.empty()
            : OptionalInt.of(2 * FROG_TICKS + (int) Math.ceil(ride));
    }

    private int packing(ServerLevel level) {
        int queued = 0;
        for (BlockPos at : packagers) {
            if (level.getBlockEntity(at) instanceof PackagerBlockEntity packager) {
                queued = Math.max(queued, packager.queuedExitingPackages.size());
            }
        }
        return (queued + 1) * PackagerBlockEntity.CYCLE;
    }

    // The wait for a train and the ride to this postbox, from whichever station of the network is slowest to reach it:
    // an order may be packed beside any of them.
    private OptionalInt mailTicks(ServerLevel level, BlockPos post, Predicate<UUID> trains) {
        Optional<GlobalStation> to = CreateMail.stationOf(level, post);
        if (to.isEmpty()) {
            return OptionalInt.empty();
        }
        int slowest = 0;
        for (GlobalStation from : CreateMail.stationsOf(level, posts.values())) {
            if (from == to.get()) {
                return OptionalInt.empty();
            }
            Optional<CreateMail.Leg> leg = CreateMail.leg(level.getServer(), from, to.get(), trains);
            if (leg.isEmpty()) {
                return OptionalInt.empty();
            }
            slowest = Math.max(slowest, leg.get().arrives());
        }
        return posts.isEmpty() ? OptionalInt.empty() : OptionalInt.of(slowest);
    }

    private static double ride(ServerLevel level, List<BlockPos> starts, BlockPos to) {
        Map<BlockPos, Double> best = new HashMap<>();
        PriorityQueue<Map.Entry<BlockPos, Double>> open = new PriorityQueue<>(Map.Entry.comparingByValue());
        for (BlockPos start : starts) {
            best.put(start, 0.0D);
            open.add(Map.entry(start, 0.0D));
        }
        while (!open.isEmpty()) {
            Map.Entry<BlockPos, Double> next = open.poll();
            BlockPos at = next.getKey();
            if (next.getValue() > best.getOrDefault(at, Double.POSITIVE_INFINITY)) {
                continue;
            }
            if (at.equals(to)) {
                return next.getValue();
            }
            if (!(level.getBlockEntity(at) instanceof ChainConveyorBlockEntity chain)) {
                continue;
            }
            double speed = speedOf(chain);
            if (speed <= 0.0D) {
                continue;
            }
            chain.prepareStats();
            for (BlockPos towards : chain.connections) {
                ChainConveyorBlockEntity.ConnectionStats stats = chain.connectionStats.get(towards);
                double length = stats == null ? Math.sqrt(towards.distSqr(BlockPos.ZERO)) : stats.chainLength();
                double reached = next.getValue() + (length + HALF_LOOP) / speed;
                BlockPos there = at.offset(towards);
                if (reached < best.getOrDefault(there, Double.POSITIVE_INFINITY)) {
                    best.put(there, reached);
                    open.add(Map.entry(there, reached));
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    // Blocks a tick a chain carries its parcels at. Create's own getSpeed reads nought while ticks are frozen, so the
    // speed the chain is driven at is read instead, and an overstressed chain counts as standing. A parcel's
    // chainPosition advances by four times that rate each tick (ChainConveyorBlockEntity.tick), so the ride is
    // reckoned at the rate the parcels actually move, not the rate the chain turns.
    private static double speedOf(ChainConveyorBlockEntity chain) {
        return chain.isOverStressed() ? 0.0D : Math.abs(chain.getTheoreticalSpeed()) / DEGREES * PARCEL_SPEED;
    }

    // Every chain conveyor joined to this one by chains, as far as the ground is loaded.
    static Set<BlockPos> net(ServerLevel level, BlockPos chain) {
        return walk(level, chain, new Flaws());
    }

    // Every chain conveyor joined to this one by chains, noting where the walk runs into unloaded ground.
    private static Set<BlockPos> walk(ServerLevel level, BlockPos start, Flaws flaws) {
        Set<BlockPos> seen = new LinkedHashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>(List.of(start));
        while (!open.isEmpty() && seen.size() < MOST_CHAINS) {
            BlockPos at = open.poll();
            if (seen.contains(at)) {
                continue;
            }
            if (!level.isLoaded(at)) {
                flaws.note(NetworkFlaw.UNLOADED, at);
                continue;
            }
            if (!(level.getBlockEntity(at) instanceof ChainConveyorBlockEntity chain)) {
                continue;
            }
            seen.add(at);
            for (BlockPos towards : chain.connections) {
                open.add(at.offset(towards));
            }
        }
        return seen;
    }

    private static final class Flaws {
        private Optional<PackageNetwork.Flaw> first = Optional.empty();

        void note(NetworkFlaw kind, BlockPos at) {
            if (first.isEmpty()) {
                first = Optional.of(new PackageNetwork.Flaw(kind, at));
            }
        }
    }
}
