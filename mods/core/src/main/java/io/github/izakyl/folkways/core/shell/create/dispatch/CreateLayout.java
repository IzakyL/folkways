package io.github.izakyl.folkways.core.shell.create.dispatch;

import com.simibubi.create.Create;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.packagePort.PackagePortTarget;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticsNetwork;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Loaded packagers, their adjacent parcel ports and the chains those ports connect to. */
public record CreateLayout(List<BlockPos> packagers, List<BlockPos> containers, Set<BlockPos> senders,
                           Set<BlockPos> chains, Map<BlockPos, BlockPos> posts,
                           List<GlobalPos> loadedLinks, Map<BlockPos, List<Port>> adjacentPorts,
                           List<BlockPos> chainSeeds, List<BlockPos> unloadedChains) {

    public record Port(BlockPos at, boolean postbox, Optional<BlockPos> chain) {
    }

    private static final int MOST_CHAINS = 1024;
    // A parcel rounds half of each conveyor it passes, on Create's loop of radius 1.5.
    private static final double HALF_LOOP = Math.PI * 1.5D;
    // Create moves a chain's parcels |rpm| / 360 * 4 blocks a tick (ChainConveyorBlockEntity.tick).
    private static final double DEGREES = 360.0D;
    private static final double PARCEL_SPEED = 4.0D;
    // A frogport's throw onto the chain, and the catch at the far end.
    private static final int FROG_TICKS = 20;

    static CreateLayout of(ServerLevel level, UUID network) {
        LogisticsNetwork wired = Create.LOGISTICS.logisticsNetworks.get(network);
        List<GlobalPos> links = wired == null ? List.of() : List.copyOf(wired.loadedLinks);
        List<BlockPos> packagers = new ArrayList<>();
        for (GlobalPos link : links) {
            if (link.dimension().equals(level.dimension()) && level.isLoaded(link.pos())) {
                packagers.addAll(packagersBeside(level, link.pos()));
            }
        }
        Set<BlockPos> senders = new LinkedHashSet<>();
        List<BlockPos> seeds = new ArrayList<>();
        Map<BlockPos, BlockPos> posts = new LinkedHashMap<>();
        Map<BlockPos, List<Port>> adjacent = new LinkedHashMap<>();
        Set<BlockPos> containers = new LinkedHashSet<>();
        for (BlockPos packager : packagers) {
            List<Port> ports = new ArrayList<>();
            for (Direction side : Direction.values()) {
                Optional<PackagePortBlockEntity> port = CreatePackageNetwork.portAt(level, packager.relative(side));
                if (port.isEmpty()) {
                    continue;
                }
                BlockPos at = port.get().getBlockPos();
                boolean postbox = CreateMail.posts(port.get());
                Optional<BlockPos> chain = chainOf(port.get());
                ports.add(new Port(at, postbox, chain));
                if (postbox) {
                    posts.putIfAbsent(packager, at);
                }
                if (postbox || chain.isPresent()) {
                    senders.add(at);
                }
                chain.ifPresent(seeds::add);
            }
            adjacent.put(packager, List.copyOf(ports));
            targetOf(level, packager).ifPresent(containers::add);
        }
        List<BlockPos> unloaded = new ArrayList<>();
        Set<BlockPos> chains = seeds.isEmpty() ? Set.of() : walk(level, seeds.getFirst(), unloaded::add);
        return new CreateLayout(List.copyOf(packagers), List.copyOf(containers),
            Collections.unmodifiableSet(senders), Collections.unmodifiableSet(chains), Map.copyOf(posts), links,
            Map.copyOf(adjacent), List.copyOf(seeds), List.copyOf(unloaded));
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

    public static boolean containersComplete(ServerLevel level, UUID network) {
        LogisticsNetwork wired = Create.LOGISTICS.logisticsNetworks.get(network);
        if (wired == null) {
            return true;
        }
        for (GlobalPos link : wired.totalLinks) {
            if (!link.dimension().equals(level.dimension())) {
                continue;
            }
            if (!wired.loadedLinks.contains(link) || !level.isLoaded(link.pos())) {
                return false;
            }
            for (Direction side : Direction.values()) {
                BlockPos beside = link.pos().relative(side);
                if (!level.isLoaded(beside)) {
                    return false;
                }
                if (level.getBlockEntity(beside) instanceof PackagerBlockEntity) {
                    Direction facing = level.getBlockState(beside).getOptionalValue(PackagerBlock.FACING)
                        .orElse(Direction.UP);
                    if (!level.isLoaded(beside.relative(facing.getOpposite()))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // The container a packager packs from and unpacks into: the block behind the way it faces, if that holds items.
    public static Optional<BlockPos> targetOf(ServerLevel level, BlockPos packager) {
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
    public static boolean unpacks(ServerLevel level, BlockPos port) {
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

    static List<BlockPos> portsOn(ServerLevel level, Set<BlockPos> chains) {
        Set<BlockPos> found = new LinkedHashSet<>();
        for (BlockPos at : chains) {
            if (level.getBlockEntity(at) instanceof ChainConveyorBlockEntity chain) {
                chain.loopPorts.keySet().forEach(offset -> found.add(at.offset(offset)));
                chain.travelPorts.keySet().forEach(offset -> found.add(at.offset(offset)));
            }
        }
        found.removeIf(port -> CreatePackageNetwork.portAt(level, port).isEmpty());
        return List.copyOf(found);
    }

    // The throw at one frogport, the ride along the chains to another, and the catch there.
    static OptionalInt throwTicks(ServerLevel level, BlockPos from, BlockPos to) {
        Optional<BlockPos> start = CreatePackageNetwork.portAt(level, from).flatMap(CreateLayout::chainOf);
        Optional<BlockPos> end = CreatePackageNetwork.portAt(level, to).flatMap(CreateLayout::chainOf);
        if (start.isEmpty() || end.isEmpty()) {
            return OptionalInt.empty();
        }
        return chainTicks(level, List.of(start.get()), end.get());
    }

    static OptionalInt chainTicks(ServerLevel level, List<BlockPos> starts, BlockPos end) {
        double ride = ride(level, starts, end);
        return Double.isInfinite(ride) ? OptionalInt.empty()
            : OptionalInt.of(2 * FROG_TICKS + (int) Math.ceil(ride));
    }

    int packing(ServerLevel level) {
        int queued = 0;
        for (BlockPos at : packagers) {
            if (level.getBlockEntity(at) instanceof PackagerBlockEntity packager) {
                queued = Math.max(queued, packager.queuedExitingPackages.size());
            }
        }
        return (queued + 1) * PackagerBlockEntity.CYCLE;
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
    static double speedOf(ServerLevel level, BlockPos at) {
        return level.getBlockEntity(at) instanceof ChainConveyorBlockEntity chain ? speedOf(chain) : 0.0D;
    }

    private static double speedOf(ChainConveyorBlockEntity chain) {
        return chain.isOverStressed() ? 0.0D : Math.abs(chain.getTheoreticalSpeed()) / DEGREES * PARCEL_SPEED;
    }

    // Every chain conveyor joined to this one by chains, as far as the ground is loaded.
    static Set<BlockPos> net(ServerLevel level, BlockPos chain) {
        return walk(level, chain, at -> {});
    }

    // Every chain conveyor joined to this one by chains, noting where the walk runs into unloaded ground.
    private static Set<BlockPos> walk(ServerLevel level, BlockPos start, Consumer<BlockPos> unloaded) {
        Set<BlockPos> seen = new LinkedHashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>(List.of(start));
        while (!open.isEmpty() && seen.size() < MOST_CHAINS) {
            BlockPos at = open.poll();
            if (seen.contains(at)) {
                continue;
            }
            if (!level.isLoaded(at)) {
                unloaded.accept(at);
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
}
