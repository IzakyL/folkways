package io.github.izakyl.folkways.assistant.tool.internal;

import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class ReadScope {

    public record Interval(long min, long max) {

        public boolean contains(long y) {
            return y >= min && y <= max;
        }
    }

    private record Row(Realm realm, int y, int z) {
    }

    private record Bounds(long x0, long y0, long z0, long x1, long y1, long z1) {

        boolean contains(BlockPos pos) {
            return pos.getX() >= x0 && pos.getX() <= x1 && pos.getY() >= y0 && pos.getY() <= y1
                    && pos.getZ() >= z0 && pos.getZ() <= z1;
        }

        boolean covers(int x, int z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }

        Bounds union(Bounds other) {
            return new Bounds(Math.min(x0, other.x0), Math.min(y0, other.y0), Math.min(z0, other.z0),
                    Math.max(x1, other.x1), Math.max(y1, other.y1), Math.max(z1, other.z1));
        }
    }

    private record Segment(BlockPos from, BlockPos to, int width, int height) {

        Bounds bounds() {
            return new Bounds((long) Math.min(from.getX(), to.getX()) - width,
                    (long) Math.min(from.getY(), to.getY()) - height,
                    (long) Math.min(from.getZ(), to.getZ()) - width,
                    (long) Math.max(from.getX(), to.getX()) + width,
                    (long) Math.max(from.getY(), to.getY()) + height,
                    (long) Math.max(from.getZ(), to.getZ()) + width);
        }

        Bounds center() {
            long x = (long) from.getX() + to.getX();
            long y = (long) from.getY() + to.getY();
            long z = (long) from.getZ() + to.getZ();
            return new Bounds(x, y, z, x, y, z);
        }

        boolean contains(BlockPos pos) {
            long dx = (long) to.getX() - from.getX();
            long dy = (long) to.getY() - from.getY();
            long dz = (long) to.getZ() - from.getZ();
            long steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
            if (dx == 0 && dz == 0 || dy == 0 && (dx == 0 || dz == 0)) {
                return bounds().contains(pos);
            }
            double low = 0;
            double high = steps;
            long[] deltas = {dx, dy, dz};
            int[] origins = {from.getX(), from.getY(), from.getZ()};
            int[] targets = {pos.getX(), pos.getY(), pos.getZ()};
            int[] padding = {width, height, width};
            for (int axis = 0; axis < 3; axis++) {
                if (deltas[axis] == 0) {
                    if (Math.abs((long) targets[axis] - origins[axis]) > padding[axis]) {
                        return false;
                    }
                    continue;
                }
                double a = ((double) targets[axis] - origins[axis] - padding[axis] - 0.5)
                        * steps / deltas[axis];
                double b = ((double) targets[axis] - origins[axis] + padding[axis] + 0.5)
                        * steps / deltas[axis];
                low = Math.max(low, Math.min(a, b));
                high = Math.min(high, Math.max(a, b));
            }
            // The dominant axis bounds this loop to twice the padding, even for a very long held line.
            for (long step = Math.max(0, (long) Math.ceil(low)); step <= Math.min(steps, (long) Math.floor(high));
                    step++) {
                double t = (double) step / steps;
                if (Math.abs(Math.round(from.getX() + dx * t) - pos.getX()) <= width
                        && Math.abs(Math.round(from.getY() + dy * t) - pos.getY()) <= height
                        && Math.abs(Math.round(from.getZ() + dz * t) - pos.getZ()) <= width) {
                    return true;
                }
            }
            return false;
        }

        Interval column(int x, int z) {
            Bounds bounds = bounds();
            if (!bounds.covers(x, z)) {
                return null;
            }
            long dx = (long) to.getX() - from.getX();
            long dy = (long) to.getY() - from.getY();
            long dz = (long) to.getZ() - from.getZ();
            if (dx == 0 && dz == 0 || dy == 0 && (dx == 0 || dz == 0)) {
                return new Interval(bounds.y0, bounds.y1);
            }
            long steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
            double low = 0;
            double high = steps;
            long[] deltas = {dx, dz};
            int[] origins = {from.getX(), from.getZ()};
            int[] targets = {x, z};
            for (int axis = 0; axis < 2; axis++) {
                if (deltas[axis] == 0) {
                    continue;
                }
                double a = ((double) targets[axis] - origins[axis] - width - 0.5) * steps / deltas[axis];
                double b = ((double) targets[axis] - origins[axis] + width + 0.5) * steps / deltas[axis];
                low = Math.max(low, Math.min(a, b));
                high = Math.min(high, Math.max(a, b));
            }
            // Include boundary samples before trimming so Math.round ties keep the point-query semantics.
            long first = Math.max(0, (long) Math.floor(low) - 1);
            long last = Math.min(steps, (long) Math.ceil(high) + 1);
            while (first <= last && !covers(x, z, first, steps, dx, dz)) {
                first++;
            }
            while (last >= first && !covers(x, z, last, steps, dx, dz)) {
                last--;
            }
            if (first > last) {
                return null;
            }
            long fromY = Math.round(from.getY() + dy * ((double) first / steps));
            long toY = Math.round(from.getY() + dy * ((double) last / steps));
            return new Interval(Math.min(fromY, toY) - height, Math.max(fromY, toY) + height);
        }

        private boolean covers(int x, int z, long step, long steps, long dx, long dz) {
            double t = (double) step / steps;
            return Math.abs(Math.round(from.getX() + dx * t) - x) <= width
                    && Math.abs(Math.round(from.getZ() + dz * t) - z) <= width;
        }
    }

    private record Node(Bounds bounds, Node left, Node right, Segment segment) {

        static Node of(List<Segment> segments) {
            Bounds bounds = segments.getFirst().bounds();
            Bounds centers = segments.getFirst().center();
            for (Segment segment : segments) {
                bounds = bounds.union(segment.bounds());
                centers = centers.union(segment.center());
            }
            if (segments.size() == 1) {
                return new Node(bounds, null, null, segments.getFirst());
            }
            int axis = centers.x1 - centers.x0 >= centers.z1 - centers.z0 ? 0 : 2;
            if (centers.y1 - centers.y0 > Math.max(centers.x1 - centers.x0, centers.z1 - centers.z0)) {
                axis = 1;
            }
            int split = axis;
            segments.sort(Comparator.comparingLong(segment -> switch (split) {
                case 0 -> (long) segment.from.getX() + segment.to.getX();
                case 1 -> (long) segment.from.getY() + segment.to.getY();
                default -> (long) segment.from.getZ() + segment.to.getZ();
            }));
            int middle = segments.size() / 2;
            return new Node(bounds, of(new ArrayList<>(segments.subList(0, middle))),
                    of(new ArrayList<>(segments.subList(middle, segments.size()))), null);
        }

        boolean contains(BlockPos pos) {
            return bounds.contains(pos) && (segment != null ? segment.contains(pos)
                    : left.contains(pos) || right.contains(pos));
        }

        void column(int x, int z, List<Interval> intervals) {
            if (!bounds.covers(x, z)) {
                return;
            }
            if (segment == null) {
                left.column(x, z, intervals);
                right.column(x, z, intervals);
            } else {
                Interval interval = segment.column(x, z);
                if (interval != null) {
                    intervals.add(interval);
                }
            }
        }
    }

    private final Map<Realm, Node> roots = new HashMap<>();

    public ReadScope(ToolContext context) {
        Map<Realm, List<Segment>> segments = new HashMap<>();
        for (var holding : context.colony().claims()) {
            if (holding.what() instanceof Claimed.Area area) {
                area(segments, area);
            } else if (holding.what() instanceof Claimed.Line line) {
                line(segments, line.points(), 8, 8);
            } else {
                for (WorldPos cell : holding.what().cells()) {
                    add(segments, cell, cell, 8, 8);
                }
            }
        }
        for (var survey : Surveys.of(context)) {
            line(segments, survey.points(), 16, 32);
        }
        segments.forEach((realm, entries) -> roots.put(realm, Node.of(entries)));
    }

    public boolean contains(ServerLevel level, BlockPos pos) {
        return contains(WorldPos.of(level, pos));
    }

    boolean contains(WorldPos address) {
        Node root = roots.get(address.realm());
        return root != null && root.contains(address.cell());
    }

    public List<Interval> column(Realm realm, int x, int z) {
        Node root = roots.get(realm);
        if (root == null) {
            return List.of();
        }
        List<Interval> intervals = new ArrayList<>();
        root.column(x, z, intervals);
        return merged(intervals);
    }

    public List<Interval> column(ServerLevel level, int x, int z) {
        Map<Realm, List<Interval>> byRealm = new HashMap<>();
        List<Interval> candidates = new ArrayList<>();
        for (Realm realm : roots.keySet()) {
            var origin = WorldSpaces.storage(level, new WorldPos(realm, BlockPos.ZERO));
            if (origin.isEmpty()) {
                continue;
            }
            long localX = (long) x - origin.get().getX();
            long localZ = (long) z - origin.get().getZ();
            if (localX < Integer.MIN_VALUE || localX > Integer.MAX_VALUE
                    || localZ < Integer.MIN_VALUE || localZ > Integer.MAX_VALUE) {
                continue;
            }
            List<Interval> intervals = new ArrayList<>();
            for (Interval local : column(realm, (int) localX, (int) localZ)) {
                long min = Math.max(level.getMinBuildHeight(), local.min + origin.get().getY());
                long max = Math.min(level.getMaxBuildHeight() - 1, local.max + origin.get().getY());
                if (min <= max) {
                    intervals.add(new Interval(min, max));
                }
            }
            if (!intervals.isEmpty()) {
                byRealm.put(realm, intervals);
                candidates.addAll(intervals);
            }
        }
        List<Interval> intervals = new ArrayList<>();
        for (Interval candidate : merged(candidates)) {
            long start = candidate.min;
            for (long y = candidate.min; y <= candidate.max; y++) {
                WorldPos address = WorldPos.of(level, new BlockPos(x, (int) y, z));
                boolean allowed = false;
                for (Interval interval : byRealm.getOrDefault(address.realm(), List.of())) {
                    if (interval.contains(y)) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    if (start < y) {
                        intervals.add(new Interval(start, y - 1));
                    }
                    start = y + 1;
                }
            }
            if (start <= candidate.max) {
                intervals.add(new Interval(start, candidate.max));
            }
        }
        return merged(intervals);
    }

    private static List<Interval> merged(List<Interval> intervals) {
        if (intervals.isEmpty()) {
            return List.of();
        }
        intervals.sort(Comparator.comparingLong(Interval::min));
        List<Interval> result = new ArrayList<>();
        Interval previous = intervals.getFirst();
        for (int index = 1; index < intervals.size(); index++) {
            Interval next = intervals.get(index);
            if (next.min <= previous.max + 1) {
                previous = new Interval(previous.min, Math.max(previous.max, next.max));
            } else {
                result.add(previous);
                previous = next;
            }
        }
        result.add(previous);
        return result.reversed();
    }

    private static void area(Map<Realm, List<Segment>> segments, Claimed.Area area) {
        Map<Row, List<Integer>> rows = new HashMap<>();
        for (WorldPos cell : area.covered()) {
            Row row = new Row(cell.realm(), cell.cell().getY(), cell.cell().getZ());
            rows.computeIfAbsent(row, ignored -> new ArrayList<>()).add(cell.cell().getX());
        }
        rows.forEach((row, cells) -> {
            cells.sort(Integer::compare);
            int start = cells.getFirst();
            int end = start;
            for (int index = 1; index < cells.size(); index++) {
                int x = cells.get(index);
                if ((long) x != (long) end + 1) {
                    addRow(segments, row, start, end);
                    start = x;
                }
                end = x;
            }
            addRow(segments, row, start, end);
        });
    }

    private static void addRow(Map<Realm, List<Segment>> segments, Row row, int start, int end) {
        add(segments, new WorldPos(row.realm, new BlockPos(start, row.y, row.z)),
                new WorldPos(row.realm, new BlockPos(end, row.y, row.z)), 8, 8);
    }

    private static void line(Map<Realm, List<Segment>> segments, List<WorldPos> points, int width, int height) {
        for (int index = 1; index < points.size(); index++) {
            add(segments, points.get(index - 1), points.get(index), width, height);
        }
    }

    private static void add(Map<Realm, List<Segment>> segments, WorldPos from, WorldPos to, int width, int height) {
        if (from.sameRealm(to)) {
            segments.computeIfAbsent(from.realm(), ignored -> new ArrayList<>())
                    .add(new Segment(from.cell(), to.cell(), width, height));
        }
    }
}
