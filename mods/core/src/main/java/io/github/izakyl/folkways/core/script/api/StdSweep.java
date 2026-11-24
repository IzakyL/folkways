package io.github.izakyl.folkways.core.script.api;

import io.github.izakyl.folkways.solve.Chains;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.Tuple;

/** Footprint-aware grid planning for the sweep library. */
public final class StdSweep implements StarlarkValue {

    static final StdSweep INSTANCE = new StdSweep();
    private static final double EPSILON = 1e-6;
    private static final double FIRST_EDGE = 1.2;
    private static final int MAX_CELLS = 512;
    private static final int MAX_APPROACH = 4096;
    private static final int MAX_GRID = 16_384;
    private static final Chains.Options IK_OPTIONS = new Chains.Options(Chains.Options.DEFAULT.iterations(),
        0.01, null, Chains.Options.DEFAULT.directionWeight());

    private StdSweep() {
    }

    @StarlarkMethod(name = "snapshot", doc = "Index the observed standing cells for routing and leg checks.",
        parameters = {@Param(name = "around"), @Param(name = "blocked")}, useStarlarkThread = true)
    public Snapshot snapshot(Sequence<?> around, Sequence<?> blocked, StarlarkThread thread) throws EvalException {
        var bounds = bounds(around, MAX_APPROACH, thread);
        if (blocked.size() > MAX_APPROACH) {
            throw Starlark.errorf("sweep snapshot has too many blocked cells");
        }
        var standing = new HashSet<BlockPos>();
        for (Object cell : blocked) {
            standing.add(BlockPos.containing(point(StdIk.sequence(cell, "blocked cell"), thread)));
        }
        return new Snapshot(bounds, standing);
    }

    @StarlarkMethod(name = "route", doc = "Find the first leg of a shortest footprint-safe route.",
        parameters = {@Param(name = "snapshot"), @Param(name = "box"), @Param(name = "reach"),
            @Param(name = "coverage"), @Param(name = "done"), @Param(name = "start"), @Param(name = "held"),
            @Param(name = "arm"), @Param(name = "ring"), @Param(name = "frame"), @Param(name = "offset"),
            @Param(name = "bias")}, useStarlarkThread = true)
    public Object route(Snapshot snapshot, Sequence<?> box, Sequence<?> reach, Sequence<?> coverage, Dict<?, ?> done,
            Sequence<?> start, Object held, Object arm, Object ring, Object frame, Sequence<?> offset, Sequence<?> bias,
            StarlarkThread thread) throws EvalException {
        var target = bounds(box, MAX_CELLS, thread);
        var half = point(reach, thread);
        if (half.x < 0 || half.y < 0 || half.z < 0) {
            throw Starlark.errorf("sweep reach must be non-negative");
        }
        var origin = point(start, thread);
        if (!inside(snapshot.bounds(), half, origin)) {
            return Starlark.NONE;
        }
        var radii = min(point(coverage, thread), half);
        var spots = new ArrayList<List<Double>>();
        var axes = new ArrayList<List<Double>>();
        long size = 1;
        for (int i = 0; i < 3; i++) {
            double radius = component(half, i);
            var line = spots(target.get(i), target.get(i + 3), radius);
            // A wide planar tool can work in the lane where the carrier actually came to rest.
            double lane = component(origin, i);
            if (i != 1 && radius > 1 && lane >= target.get(i) + radius
                    && lane <= target.get(i + 3) + 1 - radius && !line.contains(lane)) {
                line.add(lane);
                line.sort(Double::compare);
            }
            if (line.isEmpty()) {
                return Starlark.NONE;
            }
            spots.add(line);
            var axis = axis(snapshot.bounds().get(i) + radius, snapshot.bounds().get(i + 3) + 1 - radius, line,
                component(origin, i), target.get(i) - radius - 0.04, target.get(i + 3) + 1 + radius + 0.04);
            axes.add(axis);
            size *= axis.size();
            if (size > MAX_GRID) {
                throw Starlark.errorf("sweep grid is too large");
            }
        }

        var candidates = new HashSet<Vec3>();
        for (double x : spots.get(0)) {
            for (double y : spots.get(1)) {
                for (double z : spots.get(2)) {
                    ScriptRuntime.checkTime();
                    var at = new Vec3(x, y, z);
                    for (BlockPos cell : covered(target, radii, at)) {
                        if (!done.containsKey(key(cell))) {
                            candidates.add(at);
                            break;
                        }
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return Starlark.NONE;
        }

        try {
            var chain = StdIk.readChain(arm, thread);
            var seed = held == Starlark.NONE ? null : chain.values(held, thread);
            var ringBounds = ring == Starlark.NONE ? List.<Double>of()
                : numbers(StdIk.sequence(ring, "ring"), thread);
            var solver = Solver.create(chain, seed, Frame.read(frame, thread), point(offset, thread), point(bias, thread),
                ringBounds);
            var search = new Search(axes, half, snapshot, target, candidates, solver, thread);
            var startPose = new Pose(origin, held == Starlark.NONE ? List.of()
                : numbers(StdIk.sequence(held, "held controls"), thread));
            return search.find(startPose);
        } catch (IllegalArgumentException invalid) {
            throw Starlark.errorf("%s", invalid.getMessage());
        }
    }

    @StarlarkMethod(name = "covered", doc = "List the target keys covered by a measured working point.",
        parameters = {@Param(name = "box"), @Param(name = "reach"), @Param(name = "coverage"), @Param(name = "at")},
        useStarlarkThread = true)
    public StarlarkList<String> covered(Sequence<?> box, Sequence<?> reach, Sequence<?> coverage, Sequence<?> at,
            StarlarkThread thread) throws EvalException {
        var target = bounds(box, MAX_CELLS, thread);
        var radii = min(point(coverage, thread), point(reach, thread));
        var keys = new ArrayList<String>();
        for (BlockPos cell : covered(target, radii, point(at, thread))) {
            keys.add(key(cell));
        }
        return StarlarkList.copyOf(thread.mutability(), keys);
    }

    private static String key(BlockPos cell) {
        return cell.getX() + "," + cell.getY() + "," + cell.getZ();
    }

    private static Iterable<BlockPos> covered(List<Double> box, Vec3 radii, Vec3 at) {
        int[] lo = new int[3];
        int[] hi = new int[3];
        for (int i = 0; i < 3; i++) {
            double p = component(at, i);
            double radius = component(radii, i);
            int cell = (int) Math.floor(p);
            // Count cell centers in the working area. A narrow tool always works the cell it is in.
            int min = Math.min(cell, (int) Math.ceil(p - radius - 0.5));
            int max = Math.max(cell, (int) Math.floor(p + radius - 0.5));
            lo[i] = Math.max(min, box.get(i).intValue());
            hi[i] = Math.min(max, box.get(i + 3).intValue());
            if (lo[i] > hi[i]) {
                return List.of();
            }
        }
        // Keep the library's x-major order in the public worked list.
        var cells = new ArrayList<BlockPos>();
        for (int x = lo[0]; x <= hi[0]; x++) {
            for (int y = lo[1]; y <= hi[1]; y++) {
                for (int z = lo[2]; z <= hi[2]; z++) {
                    cells.add(new BlockPos(x, y, z));
                }
            }
        }
        return cells;
    }

    private static List<Double> bounds(Sequence<?> sequence, int limit, StarlarkThread thread) throws EvalException {
        var box = numbers(sequence, thread);
        if (box.size() != 6) {
            throw Starlark.errorf("expected six sweep bounds");
        }
        double volume = 1;
        for (int i = 0; i < 3; i++) {
            double low = box.get(i);
            double high = box.get(i + 3);
            if (low <= Integer.MIN_VALUE || high >= Integer.MAX_VALUE || low != Math.floor(low)
                    || high != Math.floor(high) || low > high) {
                throw Starlark.errorf("expected ordered integer sweep bounds");
            }
            volume *= high - low + 1;
        }
        if (volume > limit) {
            throw Starlark.errorf("sweep bounds exceed %d cells", limit);
        }
        return box;
    }

    private static List<Double> spots(double lo, double hi, double half) {
        double a = lo + half;
        double b = hi + 1 - half;
        if (a > b) {
            return List.of();
        }
        double margin = Math.min(0.20, Math.min(half / 2, (b - a) / 2));
        var spots = new ArrayList<Double>();
        double previous = Double.NaN;
        for (double cell = lo; cell <= hi; cell++) {
            double center = Math.max(a + margin, Math.min(b - margin, cell + 0.5));
            if (center != previous) {
                spots.add(center);
                previous = center;
            }
        }
        return spots;
    }

    private static List<Double> axis(double low, double high, List<Double> spots, double start,
            double nearFace, double farFace) throws EvalException {
        if (spots.getFirst() - low > MAX_APPROACH || high - spots.getLast() > MAX_APPROACH) {
            throw Starlark.errorf("sweep target is too far from its snapshot");
        }
        var values = new TreeSet<>(spots);
        for (int n = 1; n <= Math.ceil(spots.getFirst() - low); n++) {
            values.add(Math.max(low, spots.getFirst() - n));
        }
        for (int n = 1; n <= Math.ceil(high - spots.getLast()); n++) {
            values.add(Math.min(high, spots.getLast() + n));
        }
        values.addAll(List.of(start, nearFace, farFace));
        return values.stream().filter(p -> p >= low && p <= high).toList();
    }

    private static Vec3 min(Vec3 a, Vec3 b) {
        return new Vec3(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z));
    }

    private static double component(Vec3 point, int axis) {
        return axis == 0 ? point.x : axis == 1 ? point.y : point.z;
    }

    public record Snapshot(List<Double> bounds, Set<BlockPos> standing) implements StarlarkValue {

        @StarlarkMethod(name = "clear", doc = "Check a swept footprint against the observed standing cells.",
            parameters = {@Param(name = "reach"), @Param(name = "start"), @Param(name = "end"),
                @Param(name = "stop"), @Param(name = "stop_reach"), @Param(name = "withdraw")},
            useStarlarkThread = true)
        public boolean clear(Sequence<?> reach, Sequence<?> start, Sequence<?> end, Sequence<?> stop,
                Sequence<?> stopHalf, boolean withdraw, StarlarkThread thread) throws EvalException {
            var half = point(reach, thread);
            var from = point(start, thread);
            var to = point(end, thread);
            if (!inside(bounds, half, from) || !inside(bounds, half, to)) {
                return false;
            }
            var own = point(stop, thread);
            var allowed = footprintBounds(point(stopHalf, thread), own, own);
            // The curve's inflated footprint still uses the uninflated working stop's allowances.
            for (BlockPos cell : footprint(half, from, to)) {
                ScriptRuntime.checkTime();
                if (standing.contains(cell) && !allowed.isInside(cell)
                        && !(withdraw && withdraws(half, from, to, cell))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class Search {

        private final List<List<Double>> axes;
        private final Vec3 reach;
        private final Snapshot snapshot;
        private final List<Double> target;
        private final Set<Vec3> candidates;
        private final Solver solver;
        private final StarlarkThread thread;
        private final Map<Vec3, Optional<Pose>> poses = new HashMap<>();
        private final Map<BlockPos, BitSet> owners = new HashMap<>();
        private final Map<Vec3, Stop> stops = new HashMap<>();
        private final Map<Edge, BitSet> edges = new HashMap<>();
        private final Set<BlockPos> departure = new HashSet<>();

        private Search(List<List<Double>> axes, Vec3 reach, Snapshot snapshot, List<Double> target,
                Set<Vec3> candidates, Solver solver, StarlarkThread thread) {
            this.axes = axes;
            this.reach = reach;
            this.snapshot = snapshot;
            this.target = target;
            this.candidates = candidates;
            this.solver = solver;
            this.thread = thread;
        }

        private Object find(Pose start) {
            for (BlockPos cell : snapshot.standing()) {
                owners.put(cell, new BitSet());
            }
            for (BlockPos cell : footprint(reach, start.point(), start.point())) {
                if (owners.containsKey(cell)) {
                    departure.add(cell.immutable());
                }
            }
            for (Vec3 at : candidates) {
                var pose = pose(at);
                if (pose.isEmpty()) {
                    continue;
                }
                var stop = new Stop(stops.size(), at, pose.get());
                stops.put(at, stop);
                for (BlockPos cell : footprint(reach, pose.get().point(), pose.get().point())) {
                    var allowed = owners.get(cell);
                    if (allowed != null) {
                        allowed.set(stop.id());
                    }
                }
            }
            if (stops.isEmpty()) {
                return Starlark.NONE;
            }

            var all = new BitSet();
            all.set(0, stops.size());
            var frontier = List.of(new Route(null, start, all, null));
            var visited = new HashMap<Cell, BitSet>();
            var rank = Comparator.comparingDouble((Stop stop) -> -stop.at().y)
                .thenComparingDouble(stop -> stop.at().distanceToSqr(start.point()));
            double inner = solver.ring().isEmpty() ? 0 : solver.ring().getFirst();
            while (!frontier.isEmpty()) {
                var following = new ArrayList<Route>();
                Stop best = null;
                Route route = null;
                for (Route from : frontier) {
                    ScriptRuntime.checkTime();
                    for (Cell cell : neighbors(from.cell(), start.point())) {
                        var at = cell.point(axes);
                        var stop = stops.get(at);
                        if (from.cell() == null && (at.distanceToSqr(start.point()) > FIRST_EDGE * FIRST_EDGE
                                || (stop == null && at.distanceToSqr(start.point()) < .05 * .05))) {
                            continue;
                        }
                        var next = pose(at);
                        if (next.isEmpty() || !clearOfRing(from.pose().aim(), next.get().aim(), inner)) {
                            continue;
                        }
                        var edge = new Edge(from.cell(), cell);
                        var allowed = edges.computeIfAbsent(edge,
                            ignored -> allowed(from.pose().point(), next.get().point(), all));
                        var candidates = (BitSet) from.stops().clone();
                        candidates.and(allowed);
                        // At this depth every previously seen stop already has an equally short or shorter path.
                        var seen = visited.computeIfAbsent(cell, ignored -> new BitSet());
                        candidates.andNot(seen);
                        if (candidates.isEmpty()) {
                            continue;
                        }
                        seen.or(candidates);
                        var path = new Route(cell, next.get(), candidates, from.first() == null ? cell : from.first());
                        if (stop != null && candidates.get(stop.id()) && (best == null || rank.compare(stop, best) < 0)) {
                            best = stop;
                            route = path;
                        }
                        following.add(path);
                    }
                }
                if (best != null) {
                    var first = route.first().point(axes);
                    var aim = StdIk.controls(pose(first).orElseThrow().aim(), thread);
                    return Tuple.of(starlark(best.at()), starlark(first), aim, starlark(best.pose().point()));
                }
                frontier = following;
            }
            return Starlark.NONE;
        }

        private Optional<Pose> pose(Vec3 at) {
            return poses.computeIfAbsent(at, point -> solver.pose(point).filter(pose ->
                inside(snapshot.bounds(), reach, pose.point())
                    && (!candidates.contains(point) || inside(target, reach, pose.point()))));
        }

        private BitSet allowed(Vec3 start, Vec3 end, BitSet all) {
            var allowed = (BitSet) all.clone();
            if (!owners.isEmpty()) {
                for (BlockPos cell : footprint(reach, start, end)) {
                    var owning = owners.get(cell);
                    if (owning != null && !(departure.contains(cell) && withdraws(reach, start, end, cell))) {
                        allowed.and(owning);
                        if (allowed.isEmpty()) {
                            break;
                        }
                    }
                }
            }
            return allowed;
        }

        private List<Cell> neighbors(Cell current, Vec3 start) {
            var neighbors = new ArrayList<Cell>();
            if (current == null) {
                var xs = near(axes.get(0), start.x);
                var ys = near(axes.get(1), start.y);
                var zs = near(axes.get(2), start.z);
                for (int x : xs) {
                    for (int y : ys) {
                        for (int z : zs) {
                            neighbors.add(new Cell(x, y, z));
                        }
                    }
                }
            } else {
                for (int axis = 0; axis < 3; axis++) {
                    var values = axes.get(axis);
                    int index = current.axis(axis);
                    for (int step : new int[] {-1, 1}) {
                        for (int next = index + step; next >= 0 && next < values.size(); next += step) {
                            if (Math.abs(values.get(next) - values.get(index)) > 1 + EPSILON) {
                                break;
                            }
                            neighbors.add(current.with(axis, next));
                        }
                    }
                }
            }
            return neighbors;
        }

        private static List<Integer> near(List<Double> axis, double at) {
            var near = new ArrayList<Integer>();
            for (int i = 0; i < axis.size(); i++) {
                if (Math.abs(axis.get(i) - at) <= FIRST_EDGE) {
                    near.add(i);
                }
            }
            return near;
        }

    }

    private static boolean clearOfRing(List<Double> a, List<Double> b, double inner) {
        if (inner == 0 || a.size() != 2) {
            return true;
        }
        double x = a.get(0);
        double y = a.get(1);
        double dx = b.get(0) - x;
        double dy = b.get(1) - y;
        double span = dx * dx + dy * dy;
        double t = span < 1e-9 ? 0 : Math.clamp(-(x * dx + y * dy) / span, 0, 1);
        // A measured pose inside the planning margin can move out without approaching the hole.
        return Math.hypot(x + t * dx, y + t * dy) + EPSILON >= Math.min(inner, Math.hypot(x, y));
    }

    private static boolean inside(List<Double> bounds, Vec3 reach, Vec3 point) {
        return point.x - reach.x >= bounds.get(0) - EPSILON && point.x + reach.x <= bounds.get(3) + 1 + EPSILON
            && point.y - reach.y >= bounds.get(1) - EPSILON && point.y + reach.y <= bounds.get(4) + 1 + EPSILON
            && point.z - reach.z >= bounds.get(2) - EPSILON && point.z + reach.z <= bounds.get(5) + 1 + EPSILON;
    }

    private static Iterable<BlockPos> footprint(Vec3 reach, Vec3 start, Vec3 end) {
        var bounds = footprintBounds(reach, start, end);
        return BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(),
            bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    private static BoundingBox footprintBounds(Vec3 reach, Vec3 start, Vec3 end) {
        var min = BlockPos.containing(Math.min(start.x, end.x) - reach.x + EPSILON,
            Math.min(start.y, end.y) - reach.y + EPSILON, Math.min(start.z, end.z) - reach.z + EPSILON);
        var max = new BlockPos(
            Math.max(min.getX(), (int) Math.ceil(Math.max(start.x, end.x) + reach.x - EPSILON) - 1),
            Math.max(min.getY(), (int) Math.ceil(Math.max(start.y, end.y) + reach.y - EPSILON) - 1),
            Math.max(min.getZ(), (int) Math.ceil(Math.max(start.z, end.z) + reach.z - EPSILON) - 1));
        return BoundingBox.fromCorners(min, max);
    }

    // The starting footprint can already touch a standing cell. A leg can withdraw from that contact
    // while every part it sweeps inside the cell stays within the original overlap.
    private static boolean withdraws(Vec3 reach, Vec3 start, Vec3 end, BlockPos cell) {
        return withdraws(start.x, end.x, reach.x, cell.getX())
            && withdraws(start.y, end.y, reach.y, cell.getY())
            && withdraws(start.z, end.z, reach.z, cell.getZ());
    }

    private static boolean withdraws(double start, double end, double reach, int cell) {
        double low = Math.max(start - reach, cell);
        double high = Math.min(start + reach, cell + 1.0);
        return high >= low && Math.max(Math.min(start, end) - reach, cell) >= low - EPSILON
            && Math.min(Math.max(start, end) + reach, cell + 1.0) <= high + EPSILON;
    }

    private record Frame(Vec3 origin, Vec3 x, Vec3 y, Vec3 z) {

        private static Frame read(Object frame, StarlarkThread thread) throws EvalException {
            if (frame == Starlark.NONE) {
                return null;
            }
            if (!(frame instanceof Dict<?, ?> fields)) {
                throw Starlark.errorf("expected a sweep frame dict");
            }
            return new Frame(field(fields, "origin", thread), field(fields, "x", thread),
                field(fields, "y", thread), field(fields, "z", thread));
        }

        private static Vec3 field(Dict<?, ?> fields, String key, StarlarkThread thread) throws EvalException {
            return point(StdIk.sequence(fields.get(key), key), thread);
        }

        private Vec3 local(Vec3 at) {
            var d = at.subtract(origin);
            return new Vec3(d.dot(x), d.dot(y), d.dot(z));
        }

        private Vec3 world(Vec3 at) {
            return origin.add(x.scale(at.x)).add(y.scale(at.y)).add(z.scale(at.z));
        }
    }

    private record Solver(StdIk.Calibrated calibrated, List<Double> seed, Frame frame, Vec3 offset, Vec3 bias,
            List<Double> ring, Chains.Vec direction, Chains.Vec normal) {

        private static Solver create(StdIk.Calibrated calibrated, List<Double> seed, Frame frame, Vec3 offset,
                Vec3 bias, List<Double> ring) {
            var direction = direction(calibrated.chain());
            return new Solver(calibrated, seed, frame, offset, bias, ring, direction,
                normal(calibrated.chain(), direction));
        }

        private static Chains.Vec direction(Chains.Chain chain) {
            var joints = chain.joints();
            if (joints.isEmpty() || joints.size() > 2 || joints.stream().anyMatch(Chains.Revolute.class::isInstance)) {
                return null;
            }
            return joints.getFirst().axis();
        }

        private static Chains.Vec normal(Chains.Chain chain, Chains.Vec direction) {
            if (direction == null || chain.joints().size() < 2) {
                return null;
            }
            var cross = direction.cross(chain.joints().getLast().axis());
            double length = cross.length();
            return length > EPSILON ? cross.scale(1 / length) : null;
        }

        private Optional<Pose> pose(Vec3 at) {
            ScriptRuntime.checkTime();
            var end = at.subtract(bias).subtract(offset);
            var goal = frame == null ? end : frame.local(end);
            var target = new Chains.Vec(goal.x, goal.y, goal.z);
            var chain = calibrated.chain();
            if (direction != null) {
                var shift = target.subtract(chain.tip());
                double off = normal == null ? shift.subtract(direction.scale(shift.dot(direction))).length()
                    : Math.abs(shift.dot(normal));
                if (off > 0.1) {
                    return Optional.empty();
                }
            }
            var solution = Chains.solve(chain, target, seed, IK_OPTIONS);
            ScriptRuntime.checkTime();
            if (solution.residual() > 0.1) {
                return Optional.empty();
            }
            var aim = calibrated.aim(solution.values());
            if (!ring.isEmpty()) {
                double squared = 0;
                for (double value : aim) {
                    squared += value * value;
                }
                double radius = Math.sqrt(squared);
                if (radius < ring.get(0) || radius > ring.get(1)) {
                    return Optional.empty();
                }
            }
            var predicted = Chains.forward(chain, solution.values()).tip();
            var point = new Vec3(predicted.x(), predicted.y(), predicted.z());
            return Optional.of(new Pose((frame == null ? point : frame.world(point)).add(offset).add(bias), aim));
        }
    }

    private static List<Double> numbers(Sequence<?> items, StarlarkThread thread) throws EvalException {
        var result = new ArrayList<Double>();
        for (Object item : items) {
            result.add(StdIk.number(item, thread));
        }
        return result;
    }

    private static Vec3 point(Sequence<?> items, StarlarkThread thread) throws EvalException {
        if (items.size() != 3) {
            throw Starlark.errorf("expected three coordinates");
        }
        return new Vec3(StdIk.number(items.get(0), thread), StdIk.number(items.get(1), thread),
            StdIk.number(items.get(2), thread));
    }

    private static Tuple starlark(Vec3 point) {
        return Tuple.of(StarlarkFloat.of(point.x), StarlarkFloat.of(point.y), StarlarkFloat.of(point.z));
    }

    private record Cell(int x, int y, int z) {

        private int axis(int axis) {
            return axis == 0 ? x : axis == 1 ? y : z;
        }

        private Cell with(int axis, int index) {
            return switch (axis) {
                case 0 -> new Cell(index, y, z);
                case 1 -> new Cell(x, index, z);
                default -> new Cell(x, y, index);
            };
        }

        private Vec3 point(List<List<Double>> axes) {
            return new Vec3(axes.get(0).get(x), axes.get(1).get(y), axes.get(2).get(z));
        }
    }

    private record Pose(Vec3 point, List<Double> aim) {
    }

    private record Stop(int id, Vec3 at, Pose pose) {
    }

    private record Edge(Cell from, Cell to) {
    }

    private record Route(Cell cell, Pose pose, BitSet stops, Cell first) {
    }
}
