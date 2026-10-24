package io.github.izakyl.folkways.plugins.groundwork.plan;

import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Action;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Budget;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Cell;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Finish;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Place;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Problem;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.State;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Summary;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Voxel;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Walk;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Witness;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Independent finite reference: only Construction data is shared with production. Movement, effects,
 * exhaustive search and complete-witness replay below do not call production Rules/Search/Verifier.
 * The verifier accepts large witnesses; exhaustive enumeration is deliberately limited to eight targets.
 */
public final class ConstructionReference {
    public static final int MAX_REFERENCE_TARGETS = 8;
    public static final int MAX_REFERENCE_CELLS = 4096;
    private static final int MAX_SEEN = 250000;
    private static final double EPSILON = 1.0E-9D;
    private static final int[][] HORIZONTAL = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};

    private ConstructionReference() {
    }

    public enum Kind {
        SOLVED, UNSAT_WITHIN_MODEL, UNKNOWN_BUDGET, UNSUPPORTED
    }

    public record Result(Kind kind, Optional<Witness> witness, long expanded, int seen, String reason) {
    }

    public record Validation(boolean valid, String reason, State replayed) {
    }

    /** Reference budgets count state expansions, not production work units or wall-clock guarantees. */
    public static final class ExpansionBudget implements Budget {
        private final int allowance;
        private int used;

        public ExpansionBudget(int allowance) {
            if (allowance < 0) throw new IllegalArgumentException("negative reference allowance");
            this.allowance = allowance;
        }

        @Override
        public boolean tryStep() {
            if (exhausted()) return false;
            used++;
            return true;
        }

        @Override
        public boolean exhausted() {
            return used >= allowance;
        }

        @Override
        public int used() {
            return used;
        }
    }

    private record Previous(State state, Action action) {
    }

    // Replay owns one mutable set. Freezing only the final answer avoids quadratic certificate replay.
    private record Facts(Set<Cell> placed, Cell workerCell, int usedMaterials) {
        State freeze() {
            return new State(placed, workerCell, usedMaterials);
        }
    }

    public static Result solve(Problem problem, int expansions) {
        return new Session(problem).advance(new ExpansionBudget(expansions));
    }

    /** The complete BFS frontier and predecessors survive budget yields. */
    public static final class Session {
        private final Problem problem;
        private final ArrayDeque<State> frontier = new ArrayDeque<>();
        private final Map<State, Previous> previous = new LinkedHashMap<>();
        private boolean unknown;
        private Result finished;
        private long expanded;

        public Session(Problem problem) {
            this.problem = problem;
            String unsupported = unsupportedRules(problem);
            if (unsupported.isEmpty() && (problem.orderedTargets().size() > MAX_REFERENCE_TARGETS
                    || problem.bounds().volume() > MAX_REFERENCE_CELLS)) {
                unsupported = "reference enumeration is limited to eight targets and 4096 voxels";
            }
            if (!unsupported.isEmpty()) {
                finished = result(Kind.UNSUPPORTED, unsupported);
                return;
            }
            unknown = wholeDomain(problem).stream().anyMatch(cell -> problem.voxel(cell) == Voxel.UNKNOWN);
            if (!stateValid(problem, problem.initialState())) {
                finished = result(unknown ? Kind.UNSUPPORTED : Kind.UNSAT_WITHIN_MODEL,
                    "initial worker has no supported collision-free stance");
            } else {
                State initial = problem.initialState();
                frontier.add(initial);
                previous.put(initial, new Previous(null, null));
            }
        }

        public Result advance(Budget budget) {
            if (finished != null) return finished;
            while (!frontier.isEmpty() && budget.tryStep()) {
                State state = frontier.removeFirst();
                expanded++;
                if (goal(problem, state)) {
                    finished = new Result(Kind.SOLVED, Optional.of(witness(state)), expanded,
                        previous.size(), "complete witness");
                    return finished;
                }
                for (Cell target : problem.orderedTargets()) {
                    if (!placeGeometry(problem, state, target)) continue;
                    List<Cell> entry = route(problem, state, problem.safeCells(), Set.of(state.workerCell()));
                    if (entry.isEmpty()) continue;
                    State after = placed(state, target);
                    List<Cell> escape = route(problem, after, Set.of(state.workerCell()), problem.safeCells());
                    if (!escape.isEmpty() && !remember(after,
                            new Previous(state, new Place(target, state.workerCell(), entry, escape)))) {
                        finished = result(Kind.UNKNOWN_BUDGET, "reference state-memory bound reached");
                        return finished;
                    }
                }
                for (Cell next : neighbors(problem, state, state.workerCell())) {
                    State after = new State(state.placed(), next, state.usedMaterials());
                    if (!remember(after, new Previous(state, new Walk(List.of(state.workerCell(), next))))) {
                        finished = result(Kind.UNKNOWN_BUDGET, "reference state-memory bound reached");
                        return finished;
                    }
                }
            }
            if (frontier.isEmpty()) {
                finished = result(unknown ? Kind.UNSUPPORTED : Kind.UNSAT_WITHIN_MODEL,
                    unknown ? "unknown voxels prevent an impossibility conclusion" : "entire finite action graph exhausted");
                return finished;
            }
            return result(Kind.UNKNOWN_BUDGET, "reference frontier retained");
        }

        private boolean remember(State state, Previous from) {
            if (previous.containsKey(state)) return true;
            if (previous.size() == MAX_SEEN) return false;
            previous.put(state, from);
            frontier.addLast(state);
            return true;
        }

        private Result result(Kind kind, String reason) {
            return new Result(kind, Optional.empty(), expanded, previous.size(), reason);
        }

        private Witness witness(State finalState) {
            List<Action> actions = new ArrayList<>();
            State at = finalState;
            for (Previous before = previous.get(at); before.state() != null; before = previous.get(at)) {
                actions.add(before.action());
                at = before.state();
            }
            Collections.reverse(actions);
            actions.add(new Finish(finalState.workerCell()));
            return new Witness(actions, wholeDomain(problem), fingerprint(problem),
                new Summary(finalState, problem.materialCount() - finalState.usedMaterials(), true));
        }
    }

    /** Replays the supplied positive paths. No hidden BFS, repair or replacement route is allowed. */
    public static Validation validate(Problem problem, Witness witness) {
        Set<Cell> placed = new LinkedHashSet<>();
        Facts state = new Facts(placed, problem.start(), 0);
        Set<Cell> targets = Set.copyOf(problem.orderedTargets());
        String unsupported = unsupportedRules(problem);
        if (!unsupported.isEmpty()) return new Validation(false, unsupported, state.freeze());
        if (!fingerprint(problem).equals(witness.inputFingerprint())) {
            return new Validation(false, "input/rule fingerprint mismatch", state.freeze());
        }
        if (!wholeDomain(problem).equals(witness.dependencies())) {
            return new Validation(false, "v1 certificate must depend on the whole finite snapshot", state.freeze());
        }
        if (!standable(problem, state, state.workerCell())) {
            return new Validation(false, "invalid initial state", state.freeze());
        }
        boolean finished = false;
        for (Action action : witness.actions()) {
            if (finished) return new Validation(false, "action after Finish", state.freeze());
            if (action instanceof Walk walk) {
                if (!walk.path().getFirst().equals(state.workerCell()) || !path(problem, state, walk.path())) {
                    return new Validation(false, "invalid directed worker walk", state.freeze());
                }
                state = new Facts(placed, walk.path().getLast(), state.usedMaterials());
            } else if (action instanceof Place place) {
                if (!place.stance().equals(state.workerCell()) || !workable(problem, state, place.targetCell(), targets)) {
                    return new Validation(false, "invalid placement preconditions", state.freeze());
                }
                if (!problem.safeCells().contains(place.entryFromSafe().getFirst())
                        || !place.entryFromSafe().getLast().equals(place.stance())
                        || !path(problem, state, place.entryFromSafe())) {
                    return new Validation(false, "invalid directed entry-from-safe evidence", state.freeze());
                }
                placed.add(place.targetCell());
                Facts after = new Facts(placed, state.workerCell(), state.usedMaterials() + 1);
                if (!place.escapeToSafe().getFirst().equals(place.stance())
                        || !problem.safeCells().contains(place.escapeToSafe().getLast())
                        || !path(problem, after, place.escapeToSafe())) {
                    return new Validation(false, "invalid immediate post-placement escape evidence", after.freeze());
                }
                state = after;
            } else if (action instanceof Finish finish) {
                if (!finish.safeCell().equals(state.workerCell()) || !targets.equals(placed)
                        || !problem.safeCells().contains(state.workerCell()) || state.usedMaterials() != placed.size()) {
                    return new Validation(false, "Finish before all targets and actual withdrawal", state.freeze());
                }
                finished = true;
            } else {
                return new Validation(false, "action outside the declared stable-block domain", state.freeze());
            }
        }
        State finalState = state.freeze();
        if (!finished || !witness.summary().finished() || !finalState.equals(witness.summary().finalState())
                || witness.summary().remainingMaterials() != problem.materialCount() - state.usedMaterials()) {
            return new Validation(false, "incomplete or forged terminal/material summary", finalState);
        }
        return new Validation(true, "complete independently replayed witness", finalState);
    }

    public static boolean goal(Problem problem, State state) {
        return stateValid(problem, state) && state.placed().containsAll(problem.orderedTargets())
            && problem.safeCells().contains(state.workerCell());
    }

    public static boolean standable(Problem problem, State state, Cell feet) {
        return standable(problem, facts(state), feet);
    }

    private static boolean standable(Problem problem, Facts state, Cell feet) {
        return problem.bounds().contains(feet) && voxel(problem, state, feet.below()) == Voxel.FULL
            && empty(problem, state, body(problem, feet));
    }

    public static boolean edge(Problem problem, State state, Cell from, Cell to) {
        return edge(problem, facts(state), from, to);
    }

    private static boolean edge(Problem problem, Facts state, Cell from, Cell to) {
        long dx = (long) to.x() - from.x();
        long dz = (long) to.z() - from.z();
        long dy = (long) to.y() - from.y();
        if (Math.abs(dx) + Math.abs(dz) != 1 || dy > problem.capability().stepUp()
                || dy < -problem.capability().drop() || !standable(problem, state, from)
                || !standable(problem, state, to)) return false;
        if (dy > 0) {
            Cell raised = new Cell(from.x(), to.y(), from.z());
            return swept(problem, state, from, raised) && swept(problem, state, raised, to);
        }
        if (dy < 0) {
            Cell over = new Cell(to.x(), from.y(), to.z());
            return swept(problem, state, from, over) && swept(problem, state, over, to);
        }
        return swept(problem, state, from, to);
    }

    public static boolean placeGeometry(Problem problem, State state, Cell target) {
        return stateValid(problem, state) && workable(problem, facts(state), target, Set.copyOf(problem.orderedTargets()));
    }

    private static boolean workable(Problem problem, Facts state, Cell target, Set<Cell> targets) {
        if (!targets.contains(target) || state.placed().contains(target) || problem.protectedCells().contains(target)
                || problem.voxel(target) != Voxel.AIR || state.usedMaterials() >= problem.materialCount()) return false;
        for (int[] direction : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
            if (voxel(problem, state, target.offset(direction[0], direction[1], direction[2])) == Voxel.UNKNOWN) return false;
        }
        if (body(problem, state.workerCell()).overlaps(target)) return false;
        Cell feet = state.workerCell();
        double dx = target.x() - feet.x();
        double dz = target.z() - feet.z();
        double dy = target.y() + 0.5D - feet.y();
        double reach = problem.capability().workReach();
        double eye = Math.min(1.62D, problem.capability().height() * 0.9D);
        if (dx * dx + dz * dz + dy * dy > reach * reach + EPSILON
                || dx * dx + dz * dz + (dy - eye) * (dy - eye) > reach * reach + EPSILON) return false;
        return sight(problem, state, feet.x() + 0.5D, feet.y() + eye, feet.z() + 0.5D,
            target.x() + 0.5D, target.y() + 0.5D, target.z() + 0.5D, target);
    }

    /** A directed positive path including both ends, or an empty list. Used by reference search only. */
    public static List<Cell> route(Problem problem, State state, Set<Cell> sources, Set<Cell> goals) {
        Facts geometry = facts(state);
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Map<Cell, Cell> back = new HashMap<>();
        sources.stream().sorted().filter(cell -> standable(problem, geometry, cell)).forEach(cell -> {
            if (!back.containsKey(cell)) {
                back.put(cell, null);
                queue.addLast(cell);
            }
        });
        while (!queue.isEmpty()) {
            Cell here = queue.removeFirst();
            if (goals.contains(here)) {
                List<Cell> path = new ArrayList<>();
                for (Cell at = here; at != null; at = back.get(at)) path.add(at);
                Collections.reverse(path);
                return List.copyOf(path);
            }
            for (Cell next : neighbors(problem, geometry, here)) {
                if (!back.containsKey(next)) {
                    back.put(next, here);
                    queue.addLast(next);
                }
            }
        }
        return List.of();
    }

    public static String fingerprint(Problem problem) {
        MessageDigest hash;
        try {
            hash = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        line(hash, "FW-CONSTRUCTION-V1");
        line(hash, "rules=" + problem.ruleVersion());
        line(hash, "box=" + xyz(problem.bounds().min()) + ":" + xyz(problem.bounds().max()));
        line(hash, "start=" + xyz(problem.start()));
        line(hash, "materials=" + problem.materialCount());
        var capability = problem.capability();
        line(hash, "cap=" + Double.toHexString(capability.width()) + "," + Double.toHexString(capability.height())
            + "," + capability.stepUp() + "," + capability.drop() + "," + Double.toHexString(capability.workReach()));
        for (Cell cell : wholeDomain(problem)) {
            line(hash, "v=" + xyz(cell) + ":" + problem.voxel(cell).name()
                + ";p=" + (problem.protectedCells().contains(cell) ? 1 : 0)
                + ";s=" + (problem.safeCells().contains(cell) ? 1 : 0));
        }
        for (Cell cell : problem.orderedTargets()) line(hash, "t=" + xyz(cell));
        return HexFormat.of().formatHex(hash.digest());
    }

    public static Set<Cell> wholeDomain(Problem problem) {
        Set<Cell> cells = new LinkedHashSet<>();
        for (int x = problem.bounds().min().x(); x <= problem.bounds().max().x(); x++) {
            for (int y = problem.bounds().min().y(); y <= problem.bounds().max().y(); y++) {
                for (int z = problem.bounds().min().z(); z <= problem.bounds().max().z(); z++) cells.add(new Cell(x, y, z));
            }
        }
        return Collections.unmodifiableSet(cells);
    }

    private static String unsupportedRules(Problem problem) {
        var capability = problem.capability();
        if (problem.ruleVersion() != Construction.RULE_VERSION || capability.width() > 1.0D
                || capability.height() > 2.0D || capability.stepUp() > 1 || capability.drop() > 3
                || capability.workReach() > 4.5D) {
            return "unsupported movement/rule capability";
        }
        if (problem.orderedTargets().stream().anyMatch(cell -> problem.voxel(cell) == Voxel.FULL)) {
            return "occupied targets require external conflict/completion reconciliation";
        }
        return "";
    }

    private static boolean stateValid(Problem problem, State state) {
        return state.usedMaterials() == state.placed().size() && state.usedMaterials() <= problem.materialCount()
            && problem.orderedTargets().containsAll(state.placed())
            && state.placed().stream().allMatch(cell -> problem.voxel(cell) == Voxel.AIR
                && !problem.protectedCells().contains(cell))
            && standable(problem, state, state.workerCell());
    }

    private static State placed(State before, Cell target) {
        Set<Cell> cells = new LinkedHashSet<>(before.placed());
        cells.add(target);
        return new State(cells, before.workerCell(), before.usedMaterials() + 1);
    }

    private static List<Cell> neighbors(Problem problem, State state, Cell from) {
        return neighbors(problem, facts(state), from);
    }

    private static List<Cell> neighbors(Problem problem, Facts state, Cell from) {
        List<Cell> out = new ArrayList<>();
        for (int[] horizontal : HORIZONTAL) {
            for (int dy = -problem.capability().drop(); dy <= problem.capability().stepUp(); dy++) {
                Cell next = from.offset(horizontal[0], dy, horizontal[1]);
                if (problem.bounds().contains(next) && edge(problem, state, from, next)) out.add(next);
            }
        }
        return out;
    }

    private static boolean path(Problem problem, Facts state, List<Cell> path) {
        if (path.isEmpty() || !standable(problem, state, path.getFirst())) return false;
        for (int index = 1; index < path.size(); index++) {
            if (!edge(problem, state, path.get(index - 1), path.get(index))) return false;
        }
        return true;
    }

    private static Voxel voxel(Problem problem, Facts state, Cell cell) {
        return state.placed().contains(cell) ? Voxel.FULL : problem.voxel(cell);
    }

    private static Facts facts(State state) {
        return new Facts(state.placed(), state.workerCell(), state.usedMaterials());
    }

    private record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        boolean overlaps(Cell cell) {
            return maxX > cell.x() + EPSILON && minX < cell.x() + 1.0D - EPSILON
                && maxY > cell.y() + EPSILON && minY < cell.y() + 1.0D - EPSILON
                && maxZ > cell.z() + EPSILON && minZ < cell.z() + 1.0D - EPSILON;
        }

        Bounds union(Bounds other) {
            return new Bounds(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
        }
    }

    private static Bounds body(Problem problem, Cell feet) {
        double half = problem.capability().width() * 0.5D;
        return new Bounds(feet.x() + 0.5D - half, feet.y(), feet.z() + 0.5D - half,
            feet.x() + 0.5D + half, feet.y() + problem.capability().height(), feet.z() + 0.5D + half);
    }

    private static boolean swept(Problem problem, Facts state, Cell from, Cell to) {
        return empty(problem, state, body(problem, from).union(body(problem, to)));
    }

    private static boolean empty(Problem problem, Facts state, Bounds bounds) {
        int lowX = (int) Math.floor(bounds.minX() + EPSILON);
        int lowY = (int) Math.floor(bounds.minY() + EPSILON);
        int lowZ = (int) Math.floor(bounds.minZ() + EPSILON);
        int highX = (int) Math.ceil(bounds.maxX() - EPSILON);
        int highY = (int) Math.ceil(bounds.maxY() - EPSILON);
        int highZ = (int) Math.ceil(bounds.maxZ() - EPSILON);
        for (int x = lowX - 1; x <= highX; x++) {
            for (int y = lowY - 1; y <= highY; y++) {
                for (int z = lowZ - 1; z <= highZ; z++) {
                    Voxel voxel = voxel(problem, state, new Cell(x, y, z));
                    if (voxel == Voxel.UNKNOWN || x >= lowX && x < highX && y >= lowY && y < highY
                            && z >= lowZ && z < highZ && voxel != Voxel.AIR) return false;
                }
            }
        }
        return true;
    }

    private static boolean sight(Problem problem, Facts state, double x0, double y0, double z0,
            double x1, double y1, double z1, Cell target) {
        for (int x = (int) Math.floor(Math.min(x0, x1)); x <= Math.floor(Math.max(x0, x1)); x++) {
            for (int y = (int) Math.floor(Math.min(y0, y1)); y <= Math.floor(Math.max(y0, y1)); y++) {
                for (int z = (int) Math.floor(Math.min(z0, z1)); z <= Math.floor(Math.max(z0, z1)); z++) {
                    Cell cell = new Cell(x, y, z);
                    if (!cell.equals(target) && intersects(x0, y0, z0, x1, y1, z1, cell)
                            && voxel(problem, state, cell) != Voxel.AIR) return false;
                }
            }
        }
        return true;
    }

    // Closed segment / closed voxel test: edge and corner ties conservatively visit both voxels.
    private static boolean intersects(double x0, double y0, double z0, double x1, double y1, double z1, Cell cell) {
        double[] starts = {x0, y0, z0};
        double[] ends = {x1, y1, z1};
        int[] low = {cell.x(), cell.y(), cell.z()};
        double enter = 0.0D;
        double leave = 1.0D;
        for (int axis = 0; axis < 3; axis++) {
            double delta = ends[axis] - starts[axis];
            if (Math.abs(delta) < EPSILON) {
                if (starts[axis] < low[axis] - EPSILON || starts[axis] > low[axis] + 1.0D + EPSILON) return false;
            } else {
                double a = (low[axis] - starts[axis]) / delta;
                double b = (low[axis] + 1.0D - starts[axis]) / delta;
                enter = Math.max(enter, Math.min(a, b));
                leave = Math.min(leave, Math.max(a, b));
                if (enter > leave + EPSILON) return false;
            }
        }
        return true;
    }

    private static void line(MessageDigest hash, String text) {
        hash.update(text.getBytes(StandardCharsets.UTF_8));
        hash.update((byte) '\n');
    }

    private static String xyz(Cell cell) {
        return cell.x() + "," + cell.y() + "," + cell.z();
    }
}
