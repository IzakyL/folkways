package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Current, local places to perform a piece from; independent of the shape or type of the building. */
final class SiteApproach {

    private static final int SPAN = 4;
    private static final int MOST_STANCES = 24;
    private static final int BELOW_SPAN = 2;
    private static final int BELOW_START = 4;
    private static final int BELOW_DROP = 24;

    private SiteApproach() {
    }

    /**
     * Planned footings are hints, since another worker may have changed them already. A scaffold rung or its top
     * is a footing just like a floor. Navigation is deliberately left to the worker inside its site turn.
     */
    static List<BlockPos> stances(ServerLevel level, BlockPos focus, Set<BlockPos> changes,
            List<BlockPos> preferred) {
        Set<BlockPos> candidates = new LinkedHashSet<>();
        for (BlockPos feet : preferred) {
            if (Reach.withinReach(feet, focus) && stands(level, feet, changes)) {
                candidates.add(feet.immutable());
            }
        }
        Set<BlockPos> planned = Set.copyOf(candidates);
        for (BlockPos feet : BlockPos.betweenClosed(focus.offset(-SPAN, -SPAN, -SPAN),
                focus.offset(SPAN, SPAN, SPAN))) {
            if (Reach.withinReach(feet, focus) && stands(level, feet, changes)) {
                candidates.add(feet.immutable());
            }
        }
        List<BlockPos> ranked = candidates.stream().sorted(Comparator
            .comparingInt((BlockPos feet) -> heightGap(focus, feet))
            .thenComparingInt(feet -> planned.contains(feet) ? 0 : 1)
            .thenComparingDouble(feet -> score(focus, feet))
            .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ))
            .toList();
        // Retain a sample of each floor as well as the best working positions. A roof with no way up must
        // not consume the entire bounded route search and hide usable ground below it.
        Set<BlockPos> selected = new LinkedHashSet<>(ranked.stream().limit(MOST_STANCES - 9).toList());
        Set<Integer> heights = new HashSet<>();
        for (BlockPos feet : ranked) {
            if (heights.add(feet.getY()) && selected.size() < MOST_STANCES) {
                selected.add(feet);
            }
        }
        for (BlockPos feet : ranked) {
            if (selected.size() >= MOST_STANCES) {
                break;
            }
            selected.add(feet);
        }
        return List.copyOf(selected);
    }

    // The planner's fallback when nothing is in reach: the nearest usable ground underneath the operation.
    // Runtime reentry must search the current ground too, since other hands can close every planned stance.
    static List<BlockPos> below(BlockPos focus, Predicate<BlockPos> stands) {
        List<BlockPos> found = new ArrayList<>();
        for (int dy = -BELOW_START; found.isEmpty() && dy >= -BELOW_DROP; dy--) {
            for (int dx = -BELOW_SPAN; dx <= BELOW_SPAN; dx++) {
                for (int dz = -BELOW_SPAN; dz <= BELOW_SPAN; dz++) {
                    BlockPos feet = focus.offset(dx, dy, dz);
                    if (stands.test(feet)) {
                        found.add(feet);
                    }
                }
            }
        }
        return found;
    }

    /** Prefer a floor beside the operation, with at most one block of comfortable work above or below it. */
    static int heightGap(BlockPos focus, BlockPos feet) {
        return Math.max(0, Math.abs(focus.getY() - feet.getY()) - 1);
    }

    static boolean stands(ServerLevel level, BlockPos feet, Set<BlockPos> changes) {
        return level.isLoaded(feet) && level.isLoaded(feet.above()) && level.isLoaded(feet.below())
            && !changes.contains(feet) && !changes.contains(feet.above()) && !changes.contains(feet.below())
            && Footing.withFeetAt(level, feet);
    }

    /** A cheap local walking estimate; changing floors costs more than moving along one. */
    static double score(BlockPos from, BlockPos feet) {
        return from.distSqr(feet) + 4.0D * Math.abs(from.getY() - feet.getY());
    }
}
