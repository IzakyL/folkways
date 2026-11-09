package io.github.izakyl.folkways.vanilla.build;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** Finds temporary access from the current world, including supported horizontal walkways. */
final class ScaffoldAccess {
    private static final int HORIZONTAL_REACH = 6;
    private static final int WORK_SPAN = 2;

    private ScaffoldAccess() {
    }

    private record Across(List<BlockPos> cells) {
        BlockPos end() {
            return cells.getLast();
        }
    }

    static Optional<List<BlockPos>> find(ServerLevel level, BlockPos focus, Predicate<BlockPos> clear,
            int minHeight, int maxHeight) {
        List<BlockPos> ends = new ArrayList<>();
        for (int dx = -WORK_SPAN; dx <= WORK_SPAN; dx++) {
            for (int dz = -WORK_SPAN; dz <= WORK_SPAN; dz++) {
                ends.add(focus.offset(dx, -2, dz));
            }
        }
        ends.sort(Comparator.comparingDouble(cell -> cell.distSqr(focus)));
        Map<BlockPos, Optional<List<BlockPos>>> columns = new HashMap<>();
        List<BlockPos> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (BlockPos end : ends) {
            if (!walkway(clear, end)) {
                continue;
            }
            ArrayDeque<Across> open = new ArrayDeque<>();
            Set<BlockPos> seen = new HashSet<>();
            open.add(new Across(List.of(end)));
            seen.add(end);
            while (!open.isEmpty()) {
                Across across = open.removeFirst();
                BlockPos origin = across.end();
                Optional<List<BlockPos>> column = columns.computeIfAbsent(origin,
                    pos -> column(level, pos, clear, minHeight, maxHeight));
                if (column.isPresent()) {
                    List<BlockPos> support = column.get();
                    int score = support.size() + 2 * (across.cells().size() - 1);
                    if (score < bestScore) {
                        List<BlockPos> built = new ArrayList<>(support);
                        List<BlockPos> bridge = across.cells().reversed();
                        built.addAll(bridge.subList(1, bridge.size()));
                        best = List.copyOf(built);
                        bestScore = score;
                    }
                }
                if (across.cells().size() > HORIZONTAL_REACH) {
                    continue;
                }
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    BlockPos next = origin.relative(direction);
                    if (seen.add(next) && walkway(clear, next)) {
                        List<BlockPos> path = new ArrayList<>(across.cells());
                        path.add(next);
                        open.addLast(new Across(List.copyOf(path)));
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean walkway(Predicate<BlockPos> clear, BlockPos cell) {
        return clear.test(cell) && clear.test(cell.above()) && clear.test(cell.above(2));
    }

    private static Optional<List<BlockPos>> column(ServerLevel level, BlockPos top, Predicate<BlockPos> clear,
            int minHeight, int maxHeight) {
        List<BlockPos> down = new ArrayList<>();
        BlockPos cell = top;
        while (down.size() < maxHeight && clear.test(cell)) {
            down.add(cell);
            cell = cell.below();
        }
        if (down.size() < minHeight || !level.hasChunkAt(cell)
                || !level.getBlockState(cell).isFaceSturdy(level, cell, Direction.UP)) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(down.reversed()));
    }
}
