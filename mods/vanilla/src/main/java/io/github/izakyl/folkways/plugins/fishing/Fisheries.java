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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

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

    // The water nearest where a fisher may stand that someone can cast into from there. Water is looked at outward
    // from the footings, ring by ring - nearest footing first, and by position within a ring - so the first that
    // works is the one to fish, and no farther water is looked at.
    private static Optional<Spot> spotIn(ServerLevel level, ZoneView zone) {
        Set<BlockPos> footings = footingsOf(level, zone);
        if (footings.isEmpty()) {
            return Optional.empty();
        }
        Set<BlockPos> seen = new HashSet<>();
        for (List<BlockPos> ring : RINGS) {
            List<BlockPos> waters = new ArrayList<>();
            for (BlockPos feet : footings) {
                for (BlockPos offset : ring) {
                    BlockPos cell = feet.offset(offset);
                    if (seen.add(cell) && !level.isOutsideBuildHeight(cell) && level.isLoaded(cell)
                            && level.getFluidState(cell).is(FluidTags.WATER)) {
                        waters.add(cell);
                    }
                }
            }
            waters.sort(Comparator.comparingLong(BlockPos::asLong));
            for (BlockPos water : waters) {
                Set<BlockPos> from = new LinkedHashSet<>(Reach.workableCells(level, water));
                from.retainAll(footings);
                from.remove(water);
                Optional<Stances> cast = Stances.of(level, from);
                if (cast.isPresent()) {
                    return Optional.of(new Spot(WorldPos.of(level, water), cast.get(),
                        bite(level.getRandom())));
                }
            }
        }
        return Optional.empty();
    }

    // Every step from a footing to water within reach, measured from the bottom of the feet to the middle of the
    // water, gathered into rings of equal reach, nearest ring first.
    private static final List<List<BlockPos>> RINGS = rings();

    private static List<List<BlockPos>> rings() {
        int span = Mth.ceil(Reach.PLAYER_BLOCK_REACH);
        Map<Double, List<BlockPos>> byReach = new TreeMap<>();
        for (int dx = -span; dx <= span; dx++) {
            for (int dy = -span; dy <= span; dy++) {
                for (int dz = -span; dz <= span; dz++) {
                    double reach = dx * dx + (dy + 0.5) * (dy + 0.5) + dz * dz;
                    if (reach <= REACH_SQR) {
                        byReach.computeIfAbsent(reach, key -> new ArrayList<>()).add(new BlockPos(dx, dy, dz));
                    }
                }
            }
        }
        return byReach.values().stream().map(List::copyOf).toList();
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

    private static int bite(RandomSource random) {
        return BITE_MIN_TICKS + random.nextInt(BITE_MAX_TICKS - BITE_MIN_TICKS + 1);
    }

}
