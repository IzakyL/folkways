package io.github.izakyl.folkways.plugins.dispatch;

import com.simibubi.create.Create;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.packagePort.PackagePortTarget;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticsNetwork;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;

/**
 * How a Create package network ships, as far as the colony relies on it: every packager on the network hands its
 * parcels to frogports beside it, and those frogports all sit on one chain net that is loaded and turning. A network
 * shaped otherwise is still read, so a click on it is understood, but it carries a flaw and nothing is ordered from it.
 */
record CreateLayout(List<BlockPos> packagers, Set<BlockPos> senders, Set<BlockPos> chains,
                    Optional<PackageNetwork.Flaw> flaw) {

    private static final int MOST_CHAINS = 1024;
    // A parcel rounds half of each conveyor it passes, on Create's loop of radius 1.5.
    private static final double HALF_LOOP = Math.PI * 1.5D;
    // Create moves a chain's parcels |rpm| / 360 blocks a tick.
    private static final double DEGREES = 360.0D;
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
        for (BlockPos packager : packagers) {
            boolean ships = false;
            for (Direction side : Direction.values()) {
                Optional<PackagePortBlockEntity> port = CreatePackageNetwork.portAt(level, packager.relative(side));
                if (port.isEmpty()) {
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
        return new CreateLayout(List.copyOf(packagers), senders, chains, flaws.first);
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

    boolean flawless() {
        return flaw.isEmpty();
    }

    // Create keys a chain's ports by where each port stands from the chain; the senders are where parcels set out.
    List<BlockPos> ports(ServerLevel level) {
        Set<BlockPos> found = new LinkedHashSet<>();
        for (BlockPos at : chains) {
            if (level.getBlockEntity(at) instanceof ChainConveyorBlockEntity chain) {
                chain.loopPorts.keySet().forEach(offset -> found.add(at.offset(offset)));
                chain.travelPorts.keySet().forEach(offset -> found.add(at.offset(offset)));
            }
        }
        found.removeAll(senders);
        found.removeIf(port -> CreatePackageNetwork.portAt(level, port).isEmpty());
        return List.copyOf(found);
    }

    /**
     * From an order to its parcels at this port: the busiest packager's queue, the throw, the shortest ride along the
     * chains at each conveyor's own speed, and the catch.
     */
    OptionalInt transitTicks(ServerLevel level, BlockPos port) {
        if (!flawless()) {
            return OptionalInt.empty();
        }
        Optional<BlockPos> target = CreatePackageNetwork.portAt(level, port).flatMap(CreateLayout::chainOf)
            .filter(chains::contains);
        if (target.isEmpty()) {
            return OptionalInt.empty();
        }
        int queued = 0;
        for (BlockPos at : packagers) {
            if (level.getBlockEntity(at) instanceof PackagerBlockEntity packager) {
                queued = Math.max(queued, packager.queuedExitingPackages.size());
            }
        }
        double ride = ride(level, target.get());
        if (Double.isInfinite(ride)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((queued + 1) * PackagerBlockEntity.CYCLE + 2 * FROG_TICKS + (int) Math.ceil(ride));
    }

    private double ride(ServerLevel level, BlockPos to) {
        Map<BlockPos, Double> best = new HashMap<>();
        PriorityQueue<Map.Entry<BlockPos, Double>> open = new PriorityQueue<>(Map.Entry.comparingByValue());
        for (BlockPos sender : senders) {
            CreatePackageNetwork.portAt(level, sender).flatMap(CreateLayout::chainOf).ifPresent(start -> {
                best.put(start, 0.0D);
                open.add(Map.entry(start, 0.0D));
            });
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
    // speed the chain is driven at is read instead, and an overstressed chain counts as standing.
    private static double speedOf(ChainConveyorBlockEntity chain) {
        return chain.isOverStressed() ? 0.0D : Math.abs(chain.getTheoreticalSpeed()) / DEGREES;
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
