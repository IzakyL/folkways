package io.github.izakyl.folkways.plugins.fishing;

import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.front.api.FrontView;
import io.github.izakyl.folkways.front.api.ZoneView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

final class Fisheries {

    static final int BITE_MIN_TICKS = 100;
    static final int BITE_MAX_TICKS = 600;

    private static final double REACH_SQR = Reach.PLAYER_BLOCK_REACH * Reach.PLAYER_BLOCK_REACH;

    private Fisheries() {
    }

    static Map<UUID, Spot> waters(FrontView front, List<ColonyView> views) {
        Map<UUID, Spot> found = new LinkedHashMap<>();
        Set<WorldPos> named = new LinkedHashSet<>();
        for (ColonyView colony : views) {
            for (ZoneView zone : front.zonesIn(colony.level())) {
                if (!zone.delegation().equals(FishingContent.FISHERY)) {
                    continue;
                }
                spotIn(colony.level(), zone)
                    .filter(spot -> named.add(spot.water()))
                    .ifPresent(spot -> found.put(zone.id(), spot));
            }
        }
        return Map.copyOf(found);
    }

    private static Optional<Spot> spotIn(ServerLevel level, ZoneView zone) {
        Set<BlockPos> footings = footingsOf(level, zone);
        if (footings.isEmpty()) {
            return Optional.empty();
        }
        for (BlockPos water : watersBeside(level, zone, footings)) {
            Set<BlockPos> from = new LinkedHashSet<>(Reach.workableCells(level, water));
            from.retainAll(footings);
            from.remove(water);
            Optional<Stances> cast = Stances.of(level, from);
            if (cast.isPresent()) {
                return Optional.of(new Spot(WorldPos.of(level, water), cast.get(),
                    bite(level.getRandom())));
            }
        }
        return Optional.empty();
    }

    private static Set<BlockPos> footingsOf(ServerLevel level, ZoneView zone) {
        List<BlockPos> marked = new ArrayList<>(zone.cells());
        marked.sort(Comparator.comparingLong(BlockPos::asLong));
        Set<BlockPos> found = new LinkedHashSet<>();
        for (BlockPos cell : marked) {
            admit(level, cell, found);
            admit(level, cell.above(), found);
        }
        return found;
    }

    private static void admit(ServerLevel level, BlockPos feet, Set<BlockPos> found) {
        if (level.isLoaded(feet) && Footing.withFeetAt(level, feet)) {
            found.add(feet.immutable());
        }
    }

    private static List<BlockPos> watersBeside(ServerLevel level, ZoneView zone,
            Set<BlockPos> footings) {
        int span = Mth.ceil(Reach.PLAYER_BLOCK_REACH);
        BlockPos low = null;
        BlockPos high = null;
        for (BlockPos cell : zone.cells()) {
            low = low == null ? cell : BlockPos.min(low, cell);
            high = high == null ? cell : BlockPos.max(high, cell);
        }
        if (low == null) {
            return List.of();
        }
        BlockPos from = new BlockPos(low.getX() - span,
            Math.max(level.getMinBuildHeight(), low.getY() - span), low.getZ() - span);
        BlockPos to = new BlockPos(high.getX() + span,
            Math.min(level.getMaxBuildHeight() - 1, high.getY() + span), high.getZ() + span);
        List<Candidate> near = new ArrayList<>();
        for (BlockPos cell : BlockPos.betweenClosed(from, to)) {
            if (!level.isLoaded(cell) || !level.getFluidState(cell).is(FluidTags.WATER)) {
                continue;
            }
            double reached = nearestFooting(footings, cell);
            if (reached <= REACH_SQR) {
                near.add(new Candidate(cell.immutable(), reached));
            }
        }
        near.sort(Comparator.comparingDouble(Candidate::distanceSqr)
            .thenComparingLong(candidate -> candidate.water().asLong()));
        List<BlockPos> ordered = new ArrayList<>(near.size());
        for (Candidate candidate : near) {
            ordered.add(candidate.water());
        }
        return List.copyOf(ordered);
    }

    private static double nearestFooting(Set<BlockPos> footings, BlockPos water) {
        Vec3 centre = Vec3.atCenterOf(water);
        double nearest = Double.MAX_VALUE;
        for (BlockPos feet : footings) {
            nearest = Math.min(nearest, Vec3.atBottomCenterOf(feet).distanceToSqr(centre));
        }
        return nearest;
    }

    private static int bite(RandomSource random) {
        return BITE_MIN_TICKS + random.nextInt(BITE_MAX_TICKS - BITE_MIN_TICKS + 1);
    }

    private record Candidate(BlockPos water, double distanceSqr) {
    }
}
