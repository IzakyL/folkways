package io.github.izakyl.folkways.plugins.build.draft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

final class ConnectedFit {
    private static final List<Direction> SIDES = List.of(Direction.NORTH, Direction.EAST,
        Direction.SOUTH, Direction.WEST);
    private static final Comparator<BlockPos> ORDER = Comparator.<BlockPos>comparingInt(BlockPos::getX)
        .thenComparingInt(BlockPos::getZ);

    private ConnectedFit() {}

    static Optional<List<BlockPos>> cells(Solid strip, Map<BlockPos, Integer> support) {
        Set<BlockPos> wanted = new LinkedHashSet<>();
        Map<BlockPos, Integer> requestedTop = new HashMap<>();
        var bounds = strip.bounds();
        support.keySet().stream().sorted(ORDER).forEach(column -> {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                if (strip.holds(column.getX() + 0.5D, y + 0.25D, column.getZ() + 0.5D)
                        || strip.holds(column.getX() + 0.5D, y + 0.75D, column.getZ() + 0.5D)) {
                    wanted.add(column);
                    requestedTop.put(column, y);
                }
            }
        });
        if (wanted.isEmpty()) {
            BlockPos best = null;
            int score = 0;
            int top = bounds.minY();
            for (BlockPos column : support.keySet().stream().sorted(ORDER).toList()) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    int covered = Octants.count(Octants.of(strip, column.getX(), y, column.getZ()));
                    if (covered > score || (column.equals(best) && covered == score && covered > 0)) {
                        score = covered;
                        best = column;
                        top = y;
                    }
                }
            }
            if (best == null) {
                return Optional.empty();
            }
            wanted.add(best);
            requestedTop.put(best, top);
        }
        Set<BlockPos> joined = new LinkedHashSet<>();
        joined.add(wanted.iterator().next());
        flood(joined, wanted);
        while (!joined.containsAll(wanted)) {
            var queue = new ArrayDeque<>(joined);
            Map<BlockPos, BlockPos> previous = new HashMap<>();
            Set<BlockPos> visited = new HashSet<>(joined);
            BlockPos reached = null;
            while (!queue.isEmpty() && reached == null) {
                BlockPos from = queue.removeFirst();
                for (Direction side : SIDES) {
                    BlockPos next = from.relative(side);
                    if (!support.containsKey(next) || !visited.add(next)) {
                        continue;
                    }
                    previous.put(next, from);
                    if (wanted.contains(next)) {
                        reached = next;
                        break;
                    }
                    queue.addLast(next);
                }
            }
            if (reached == null) {
                return Optional.empty();
            }
            while (!joined.contains(reached)) {
                joined.add(reached);
                reached = previous.get(reached);
            }
            flood(joined, wanted);
        }
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos column : joined) {
            int bottom = support.get(column) + 1;
            int top = Math.max(bottom, requestedTop.getOrDefault(column, bottom));
            if (!requestedTop.containsKey(column)) {
                for (Direction side : SIDES) {
                    top = Math.max(top, requestedTop.getOrDefault(column.relative(side), bottom));
                }
            }
            for (Direction side : SIDES) {
                BlockPos next = column.relative(side);
                if (joined.contains(next)) {
                    top = Math.max(top, support.get(next) + 1);
                }
            }
            for (int y = bottom; y <= top; y++) {
                cells.add(new BlockPos(column.getX(), y, column.getZ()));
            }
        }
        return Optional.of(cells);
    }

    private static void flood(Set<BlockPos> joined, Set<BlockPos> wanted) {
        var queue = new ArrayDeque<>(joined);
        while (!queue.isEmpty()) {
            for (Direction side : SIDES) {
                BlockPos next = queue.peekFirst().relative(side);
                if (wanted.contains(next) && joined.add(next)) {
                    queue.addLast(next);
                }
            }
            queue.removeFirst();
        }
    }
}
