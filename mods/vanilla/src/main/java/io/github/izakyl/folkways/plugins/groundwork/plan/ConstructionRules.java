package io.github.izakyl.folkways.plugins.groundwork.plan;

import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Action;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Box;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Budget;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Capability;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Cell;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Finish;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Place;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Problem;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.State;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Voxel;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Walk;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** A deliberately restricted directed voxel model. No world reads, callbacks, or hidden graph searches. */
public final class ConstructionRules {

    private static final double EPSILON = 1.0E-9D;
    private static final int[][] HORIZONTAL = {{-1, 0}, {0, -1}, {0, 1}, {1, 0}};
    private static final int[][] SIDES = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    private ConstructionRules() {
    }

    public static boolean supported(Problem problem) {
        Capability body = problem.capability();
        return problem.ruleVersion() == Construction.RULE_VERSION && body.width() <= 1.0D
            && body.height() <= 2.0D && body.stepUp() <= 1 && body.drop() <= 3 && body.workReach() <= 4.5D;
    }

    public static Voxel voxel(Problem problem, State state, Cell cell) {
        return state.placed().contains(cell) ? Voxel.FULL : problem.voxel(cell);
    }

    public static double eyeHeight(Capability body) {
        return Math.min(1.62D, body.height() * 0.9D);
    }

    public static boolean standable(Problem problem, State state, Cell feet) {
        return supported(problem) && problem.bounds().contains(feet)
            && voxel(problem, state, feet.below()) == Voxel.FULL
            && clearSweep(problem, state, feet.x() + 0.5D, feet.y(), feet.z() + 0.5D,
                feet.x() + 0.5D, feet.y(), feet.z() + 0.5D);
    }

    public static List<Cell> moves(Problem problem, State state, Cell from) {
        if (!standable(problem, state, from)) return List.of();
        List<Cell> moves = new ArrayList<>();
        for (int[] side : HORIZONTAL) {
            for (int dy = problem.capability().stepUp(); dy >= -problem.capability().drop(); dy--) {
                Cell to = from.offset(side[0], dy, side[1]);
                if (legalMove(problem, state, from, to)) moves.add(to);
            }
        }
        return List.copyOf(moves);
    }

    public static boolean legalMove(Problem problem, State state, Cell from, Cell to) {
        long dx = (long) to.x() - from.x();
        long dy = (long) to.y() - from.y();
        long dz = (long) to.z() - from.z();
        if (Math.abs(dx) + Math.abs(dz) != 1L || dy > problem.capability().stepUp()
                || dy < -problem.capability().drop() || !standable(problem, state, from)
                || !standable(problem, state, to)) return false;
        double ax = from.x() + 0.5D, az = from.z() + 0.5D;
        double bx = to.x() + 0.5D, bz = to.z() + 0.5D;
        if (dy > 0L) {
            return clearSweep(problem, state, ax, from.y(), az, ax, to.y(), az)
                && clearSweep(problem, state, ax, to.y(), az, bx, to.y(), bz);
        }
        return clearSweep(problem, state, ax, from.y(), az, bx, from.y(), bz)
            && clearSweep(problem, state, bx, from.y(), bz, bx, to.y(), bz);
    }

    // The axis-aligned swept body is exact for each horizontal/vertical segment, including head clearance.
    private static boolean clearSweep(Problem problem, State state, double ax, double ay, double az,
            double bx, double by, double bz) {
        double half = problem.capability().width() / 2.0D;
        int minX = floor(Math.min(ax, bx) - half + EPSILON);
        int maxX = floor(Math.max(ax, bx) + half - EPSILON);
        int minY = floor(Math.min(ay, by) + EPSILON);
        int maxY = floor(Math.max(ay, by) + problem.capability().height() - EPSILON);
        int minZ = floor(Math.min(az, bz) - half + EPSILON);
        int maxZ = floor(Math.max(az, bz) + half - EPSILON);
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int y = minY - 1; y <= maxY + 1; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    Voxel value = voxel(problem, state, new Cell(x, y, z));
                    if (value == Voxel.UNKNOWN || x >= minX && x <= maxX && y >= minY && y <= maxY
                            && z >= minZ && z <= maxZ && value != Voxel.AIR) return false;
                }
            }
        }
        return true;
    }

    public static boolean canPlaceLocal(Problem problem, State state, Cell target, Cell stance) {
        if (!supported(problem) || !state.workerCell().equals(stance) || !standable(problem, state, stance)
                || !problem.orderedTargets().contains(target) || problem.protectedCells().contains(target)
                || state.placed().contains(target) || problem.voxel(target) != Voxel.AIR
                || state.usedMaterials() >= problem.materialCount()) return false;
        for (int[] side : SIDES) {
            if (voxel(problem, state, target.offset(side[0], side[1], side[2])) == Voxel.UNKNOWN) return false;
        }
        double dx = (double) target.x() - stance.x();
        double dy = target.y() + 0.5D - stance.y();
        double dz = (double) target.z() - stance.z();
        double reach = problem.capability().workReach();
        double eye = eyeHeight(problem.capability());
        if (dx * dx + dy * dy + dz * dz > reach * reach
                || dx * dx + (dy - eye) * (dy - eye) + dz * dz > reach * reach
                || !lineOfSight(problem, state, stance, target)) return false;
        return target.x() != stance.x() || target.z() != stance.z()
            || target.y() < stance.y() || target.y() >= stance.y() + problem.capability().height();
    }

    /** A conservative supercover segment; touching an unknown/solid intermediate voxel blocks sight. */
    public static boolean lineOfSight(Problem problem, State state, Cell stance, Cell target) {
        double[] a = {stance.x() + 0.5D, stance.y() + eyeHeight(problem.capability()), stance.z() + 0.5D};
        double[] b = {target.x() + 0.5D, target.y() + 0.5D, target.z() + 0.5D};
        int[] at = {floor(a[0]), floor(a[1]), floor(a[2])};
        int[] end = {target.x(), target.y(), target.z()};
        int[] step = new int[3];
        double[] next = new double[3];
        double[] delta = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double difference = b[axis] - a[axis];
            step[axis] = difference > 0.0D ? 1 : difference < 0.0D ? -1 : 0;
            delta[axis] = step[axis] == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / difference);
            next[axis] = step[axis] == 0 ? Double.POSITIVE_INFINITY
                : ((step[axis] > 0 ? at[axis] + 1.0D : at[axis]) - a[axis]) / difference;
        }
        for (int visited = 0; visited < 64; visited++) {
            Cell current = new Cell(at[0], at[1], at[2]);
            if (!current.equals(target) && voxel(problem, state, current) != Voxel.AIR) return false;
            if (at[0] == end[0] && at[1] == end[1] && at[2] == end[2]) return true;
            double first = Math.min(next[0], Math.min(next[1], next[2]));
            if (first > 1.0D + EPSILON) return false;
            int tied = 0;
            for (int axis = 0; axis < 3; axis++) if (Math.abs(next[axis] - first) <= EPSILON) tied |= 1 << axis;
            for (int mask = 1; mask < 8; mask++) {
                if ((mask & tied) != mask) continue;
                Cell touched = new Cell(at[0] + ((mask & 1) != 0 ? step[0] : 0),
                    at[1] + ((mask & 2) != 0 ? step[1] : 0), at[2] + ((mask & 4) != 0 ? step[2] : 0));
                if (!touched.equals(target) && voxel(problem, state, touched) != Voxel.AIR) return false;
            }
            for (int axis = 0; axis < 3; axis++) {
                if ((tied & 1 << axis) != 0) {
                    at[axis] += step[axis];
                    next[axis] += delta[axis];
                }
            }
        }
        return false;
    }

    /** Local replay only. Entry/escape evidence and the whole suffix are verified by a sliced verifier. */
    public static State applyLocal(Problem problem, State state, Action action) {
        if (action instanceof Walk walk) {
            Cell from = state.workerCell();
            if (!walk.path().getFirst().equals(from)) throw new IllegalArgumentException("walk starts elsewhere");
            for (int i = 1; i < walk.path().size(); i++) {
                Cell to = walk.path().get(i);
                if (!legalMove(problem, state, from, to)) throw new IllegalArgumentException("invalid directed edge");
                from = to;
            }
            return new State(state.placed(), from, state.usedMaterials());
        }
        if (action instanceof Place place) {
            if (!canPlaceLocal(problem, state, place.targetCell(), place.stance())) {
                throw new IllegalArgumentException("invalid local placement");
            }
            LinkedHashSet<Cell> placed = new LinkedHashSet<>(state.placed());
            placed.add(place.targetCell());
            return new State(placed, state.workerCell(), state.usedMaterials() + 1);
        }
        if (action instanceof Finish finish && finish.safeCell().equals(state.workerCell())
                && problem.safeCells().contains(finish.safeCell()) && standable(problem, state, finish.safeCell())
                && state.placed().containsAll(problem.orderedTargets())
                && state.usedMaterials() == state.placed().size()
                && state.usedMaterials() <= problem.materialCount()) return state;
        throw new IllegalArgumentException("invalid construction action");
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    public static Cell cellAt(Box box, long index) {
        long ys = (long) box.max().y() - box.min().y() + 1L;
        long zs = (long) box.max().z() - box.min().z() + 1L;
        return new Cell((int) (box.min().x() + index / (ys * zs)),
            (int) (box.min().y() + index / zs % ys), (int) (box.min().z() + index % zs));
    }

    private static String position(Cell cell) {
        return cell.x() + "," + cell.y() + "," + cell.z();
    }

    public static final class Fingerprint {
        private final Problem problem;
        private final MessageDigest digest;
        private final String[] headers;
        private int header;
        private long cell;
        private int target;
        private String result;

        public Fingerprint(Problem problem) {
            this.problem = problem;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException(unavailable);
            }
            Capability c = problem.capability();
            headers = new String[] {"FW-CONSTRUCTION-V1", "rules=" + problem.ruleVersion(),
                "box=" + position(problem.bounds().min()) + ":" + position(problem.bounds().max()),
                "start=" + position(problem.start()), "materials=" + problem.materialCount(),
                "cap=" + Double.toHexString(c.width()) + "," + Double.toHexString(c.height()) + ","
                    + c.stepUp() + "," + c.drop() + "," + Double.toHexString(c.workReach())};
        }

        public boolean advance(Budget budget) {
            while (result == null && budget.tryStep()) {
                String line;
                if (header < headers.length) {
                    line = headers[header++];
                } else if (cell < problem.bounds().volume()) {
                    Cell at = cellAt(problem.bounds(), cell++);
                    line = "v=" + position(at) + ":" + problem.voxel(at) + ";p="
                        + (problem.protectedCells().contains(at) ? 1 : 0) + ";s="
                        + (problem.safeCells().contains(at) ? 1 : 0);
                } else if (target < problem.orderedTargets().size()) {
                    line = "t=" + position(problem.orderedTargets().get(target++));
                } else {
                    result = HexFormat.of().formatHex(digest.digest());
                    break;
                }
                digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return result != null;
        }

        public Optional<String> value() {
            return Optional.ofNullable(result);
        }
    }

    /** Small pure fixtures only; runtime callers use the resumable Fingerprint. */
    public static String fingerprint(Problem problem) {
        Fingerprint scan = new Fingerprint(problem);
        scan.advance(new Budget() {
            private int used;

            public boolean tryStep() { used++; return true; }
            public boolean exhausted() { return false; }
            public int used() { return used; }
        });
        return scan.value().orElseThrow();
    }
}
