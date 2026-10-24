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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/** Bounded, resumable whole-tail search. Finding one safe placement is never a publishable result. */
public final class CompletionSearch {
    public enum Status { SEARCHING, READY, UNKNOWN, UNSUPPORTED, EXHAUSTED_WITHIN_MODEL }

    private static final long MOST_STATES = 200000L;
    private final Problem problem;
    private final ConstructionRules.Fingerprint fingerprint;
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final List<Action> actions = new ArrayList<>();
    private final Set<Cell> dependencies = new LinkedHashSet<>();
    private final List<Cell> order = new ArrayList<>();
    private final Geometry geometry = new Geometry();
    private final PriorityQueue<Cell> ranked;
    private Ranking ranking;
    private State state;
    private Status status = Status.SEARCHING;
    private String reason = "searching for a complete construction and retreat sequence";
    private Witness witness;
    private long expandedStates;
    private long backtracks;
    private boolean unknownVoxels;
    private int stage;
    private int x;
    private int y;
    private int z;
    private long indexedStances;
    private long candidateBuckets;
    private long candidateStances;

    public CompletionSearch(Problem problem) {
        this.problem = Objects.requireNonNull(problem);
        fingerprint = new ConstructionRules.Fingerprint(problem);
        state = problem.initialState();
        x = problem.bounds().min().x();
        y = problem.bounds().min().y();
        z = problem.bounds().min().z();
        ranked = new PriorityQueue<>(Comparator.comparingLong(this::groupWave)
            .thenComparingLong(this::geodesicWave)
            .thenComparingInt(Cell::y)
            .thenComparing(Comparator.comparingDouble((Cell cell) -> apart(cell, problem.start())).reversed())
            .thenComparing(Comparator.naturalOrder()));
    }

    public Status status() { return status; }
    public String reason() { return reason; }
    public Optional<Witness> witness() { return Optional.ofNullable(witness); }
    public long expandedStates() { return expandedStates; }
    public long backtracks() { return backtracks; }
    public int depth() { return frames.size(); }
    public int placedCount() { return state.placed().size(); }
    public int actionCount() { return actions.size(); }
    public int cachedMoveEntries() { return geometry.moves.size(); }
    public long indexedStances() { return indexedStances; }
    public long candidateBuckets() { return candidateBuckets; }
    public long candidateStances() { return candidateStances; }
    public Optional<Cell> currentTarget() { return Optional.ofNullable(frames.isEmpty() ? null : frames.peek().target); }
    public String stage() { return stage < 4 ? "input/" + stage : "tail/" + (frames.isEmpty() ? "exhausted" : frames.peek().stage); }


    public Status advance(Budget budget) {
        while (status == Status.SEARCHING && !budget.exhausted()) {
            switch (stage) {
                case 0 -> {
                    if (!budget.tryStep()) return status;
                    if (!ConstructionRules.supported(problem) || problem.capability().workReach() > 8.0D) {
                        return stop(Status.UNSUPPORTED, "rules, body, material or work reach is outside this declared model");
                    }
                    if (problem.materialCount() < problem.orderedTargets().size()) {
                        return stop(Status.UNKNOWN, "the declared material ledger cannot supply the complete target set");
                    }
                    if (problem.safeCells().isEmpty() || !ConstructionRules.standable(problem, state, state.workerCell())) {
                        return stop(Status.UNKNOWN, "a current legal worker footing and stable safe anchor are required");
                    }
                    stage++;
                }
                case 1 -> {
                    if (!fingerprint.advance(budget)) return status;
                    stage++;
                }
                case 2 -> {
                    if (!budget.tryStep()) return status;
                    Cell cell = new Cell(x, y, z);
                    dependencies.add(cell);
                    unknownVoxels |= problem.voxel(cell) == Voxel.UNKNOWN;
                    if (z < problem.bounds().max().z()) z++;
                    else if (y < problem.bounds().max().y()) { z = problem.bounds().min().z(); y++; }
                    else if (x < problem.bounds().max().x()) { z = problem.bounds().min().z(); y = problem.bounds().min().y(); x++; }
                    else stage++;
                }
                case 3 -> {
                    if (ranking == null) ranking = new Ranking();
                    if (!ranking.advance(budget)) return status;
                    ranking = null;
                    frames.push(new Frame(null, null, 0, 0));
                    expandedStates = 1;
                    stage++;
                }
                default -> advanceTail(budget);
            }
        }
        return status;
    }

    private void advanceTail(Budget budget) {
        while (status == Status.SEARCHING && !budget.exhausted()) {
            Frame frame = frames.peek();
            if (frame == null) {
                stop(!unknownVoxels && problem.orderedTargets().size() <= 12 ? Status.EXHAUSTED_WITHIN_MODEL : Status.UNKNOWN,
                    unknownVoxels ? "unknown cells prevent a real-world impossibility conclusion"
                        : "no complete sequence was found in this explicitly bounded model");
                return;
            }
            switch (frame.stage) {
                case 0 -> {
                    if (!budget.tryStep()) return;
                    if (state.placed().size() == order.size()) {
                        frame.escape = new Navigation(state, List.of(state.workerCell()), problem.safeCells()::contains);
                        frame.stage = 8;
                    } else {
                        frame.walk = new Navigation(state, List.of(state.workerCell()), null);
                        frame.stage++;
                    }
                }
                case 1 -> {
                    if (!frame.walk.advance(budget)) return;
                    if (problem.safeCells().size() == 1 && problem.safeCells().contains(state.workerCell())) frame.entry = frame.walk;
                    else frame.entry = new Navigation(state, problem.safeCells(), null);
                    frame.stage++;
                }
                case 2 -> {
                    if (!frame.entry.advance(budget)) return;
                    if (frame.index == null) frame.index = new StanceIndex(frame.walk.parents, frame.entry.parents);
                    if (!frame.index.advance(budget)) return;
                    frame.stage++;
                }
                case 3 -> {
                    if (frame.target == null) {
                        if (!budget.tryStep()) return;
                        if (frame.targetIndex == order.size()) {
                            backtrack();
                            continue;
                        }
                        Cell next = order.get(frame.targetIndex++);
                        if (state.placed().contains(next)) continue;
                        frame.target = next;
                        frame.candidates = new Candidates(next);
                    }
                    if (!frame.candidates.advance(budget, frame.index)) return;
                    if (frame.candidates.ready.isEmpty()) {
                        frame.target = null;
                        frame.candidates = null;
                        continue;
                    }
                    if (!budget.tryStep()) return;
                    Cell stance = frame.candidates.ready.remove();
                    State standing = new State(state.placed(), stance, state.usedMaterials());
                    if (!ConstructionRules.canPlaceLocal(problem, standing, frame.target, stance)) continue;
                    frame.chosen = stance;
                    frame.after = ConstructionRules.applyLocal(problem, standing,
                        new Place(frame.target, stance, List.of(stance), List.of(stance)));
                    geometry.invalidate(frame.target);
                    frame.escape = new Navigation(frame.after, List.of(stance), problem.safeCells()::contains);
                    frame.stage++;
                }
                case 4 -> {
                    if (!frame.escape.advance(budget)) return;
                    if (frame.escape.found == null) {
                        geometry.invalidate(frame.target);
                        frame.escape = null;
                        frame.after = null;
                        frame.stage = 3;
                    } else {
                        frame.walkTrace = new Trace(frame.walk.parents, frame.chosen);
                        frame.stage++;
                    }
                }
                case 5 -> {
                    if (!frame.walkTrace.advance(budget)) return;
                    frame.entryTrace = new Trace(frame.entry.parents, frame.chosen);
                    frame.stage++;
                }
                case 6 -> {
                    if (!frame.entryTrace.advance(budget)) return;
                    frame.escapeTrace = new Trace(frame.escape.parents, frame.escape.found);
                    frame.stage++;
                }
                case 7 -> {
                    if (!frame.escapeTrace.advance(budget)) return;
                    if (!budget.tryStep()) return;
                    if (++expandedStates > MOST_STATES || actions.size() + 3L > Construction.MAX_ACTIONS) {
                        stop(Status.UNKNOWN, "the bounded search state or witness memory allowance was reached");
                        return;
                    }
                    int base = actions.size();
                    List<Cell> walk = frame.walkTrace.path();
                    if (walk.size() > 1) actions.add(new Walk(walk));
                    actions.add(new Place(frame.target, frame.chosen, frame.entryTrace.path(), frame.escapeTrace.path()));
                    Frame child = new Frame(frame.target, state.workerCell(), state.usedMaterials(), base);
                    state = frame.after;
                    frame.releaseNavigation();
                    frames.push(child);
                }
                case 8 -> {
                    if (!frame.escape.advance(budget)) return;
                    if (frame.escape.found == null) backtrack();
                    else {
                        frame.escapeTrace = new Trace(frame.escape.parents, frame.escape.found);
                        frame.stage++;
                    }
                }
                case 9 -> {
                    if (!frame.escapeTrace.advance(budget)) return;
                    if (!budget.tryStep()) return;
                    List<Cell> exit = frame.escapeTrace.path();
                    if (exit.size() > 1) actions.add(new Walk(exit));
                    state = new State(state.placed(), exit.getLast(), state.usedMaterials());
                    actions.add(new Finish(exit.getLast()));
                    witness = new Witness(actions, dependencies, fingerprint.value().orElseThrow(),
                        new Summary(state, problem.materialCount() - state.usedMaterials(), true));
                    status = Status.READY;
                    reason = "a complete sequence with entry, immediate escape and terminal retreat evidence was found";
                }
                default -> throw new IllegalStateException("unknown construction search stage");
            }
        }
    }

    private void backtrack() {
        backtracks++;
        Frame failed = frames.pop();
        if (failed.entered == null) return;
        Set<Cell> placed = new HashSet<>(state.placed());
        placed.remove(failed.entered);
        geometry.invalidate(failed.entered);
        state = new State(placed, failed.previousWorker, failed.previousUse);
        actions.subList(failed.actionBase, actions.size()).clear();
        Frame parent = frames.peek();
        if (parent != null) {
            parent.releaseNavigation();
            parent.stage = 0;
        }
    }

    private Status stop(Status answer, String message) {
        status = answer;
        reason = message;
        return answer;
    }

    private static double apart(Cell a, Cell b) {
        double x = (double) a.x() - b.x();
        double y = (double) a.y() - b.y();
        double z = (double) a.z() - b.z();
        return x * x + y * y + z * z;
    }

    private long wave(Cell cell) {
        return (long) cell.y() - Math.abs((long) cell.x() - problem.start().x())
            - Math.abs((long) cell.z() - problem.start().z());
    }

    private long groupWave(Cell cell) {
        return ranking.groupMinimum[ranking.groups[ranking.targets.get(cell)]];
    }

    private long geodesicWave(Cell cell) {
        Integer distance = ranking.columns.get(new Cell(cell.x(), 0, cell.z()));
        return distance == null ? wave(cell) : (long) cell.y() - distance;
    }

    // These are only ordering groups, not claims of independent engineering or partial certificates.
    // Every target still belongs to the same state and the final whole-tail replay. Initial directed
    // entry distances distinguish a winding passage's actual far end from its geometric far end.
    private final class Ranking {
        private static final int[][] SIDES = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0},
            {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        final Map<Cell, Integer> targets = new HashMap<>();
        final Map<Cell, Integer> columns = new HashMap<>();
        final int[] groups = new int[problem.orderedTargets().size()];
        final long[] groupMinimum = new long[groups.length + 1];
        private final ArrayDeque<Cell> frontier = new ArrayDeque<>();
        private Navigation initial;
        private Iterator<Map.Entry<Cell, Integer>> distances;
        private Cell current;
        private int group;
        private int scan;
        private int target;
        private int side;
        private int stage;

        boolean advance(Budget budget) {
            while (!budget.exhausted()) {
                switch (stage) {
                    case 0 -> {
                        if (!budget.tryStep()) return false;
                        if (target < problem.orderedTargets().size()) {
                            Cell next = problem.orderedTargets().get(target);
                            if (problem.voxel(next) == Voxel.FULL) {
                                stop(Status.UNSUPPORTED, "an already-filled target needs live conflict and material reconciliation");
                                return false;
                            }
                            targets.put(next, target++);
                        } else stage++;
                    }
                    case 1 -> {
                        if (!budget.tryStep()) return false;
                        if (current != null) {
                            int[] offset = SIDES[side++];
                            Cell next = current.offset(offset[0], offset[1], offset[2]);
                            Integer index = targets.get(next);
                            if (index != null && groups[index] == 0) {
                                groups[index] = group;
                                groupMinimum[group] = Math.min(groupMinimum[group], wave(next));
                                frontier.addLast(next);
                            }
                            if (side == SIDES.length) current = null;
                        } else if (!frontier.isEmpty()) {
                            current = frontier.removeFirst();
                            side = 0;
                        } else if (scan < groups.length) {
                            int index = scan++;
                            if (groups[index] == 0) {
                                Cell seed = problem.orderedTargets().get(index);
                                groups[index] = ++group;
                                groupMinimum[group] = wave(seed);
                                frontier.addLast(seed);
                            }
                        } else {
                            initial = new Navigation(state, problem.safeCells(), null, true);
                            stage++;
                        }
                    }
                    case 2 -> {
                        if (!initial.advance(budget)) return false;
                        distances = initial.distances.entrySet().iterator();
                        stage++;
                    }
                    case 3 -> {
                        if (!budget.tryStep()) return false;
                        if (distances.hasNext()) {
                            Map.Entry<Cell, Integer> entry = distances.next();
                            Cell feet = entry.getKey();
                            columns.merge(new Cell(feet.x(), 0, feet.z()), entry.getValue(), Math::min);
                        } else {
                            initial = null;
                            distances = null;
                            target = 0;
                            stage++;
                        }
                    }
                    case 4 -> {
                        if (!budget.tryStep()) return false;
                        if (target < problem.orderedTargets().size()) ranked.add(problem.orderedTargets().get(target++));
                        else stage++;
                    }
                    default -> {
                        if (!budget.tryStep()) return false;
                        if (!ranked.isEmpty()) order.add(ranked.remove());
                        else return true;
                    }
                }
            }
            return false;
        }
    }

    private final class Frame {
        final Cell entered;
        final Cell previousWorker;
        final int previousUse;
        final int actionBase;
        int stage;
        int targetIndex;
        Cell target;
        Cell chosen;
        State after;
        Navigation walk;
        Navigation entry;
        Navigation escape;
        Trace walkTrace;
        Trace entryTrace;
        Trace escapeTrace;
        StanceIndex index;
        Candidates candidates;

        Frame(Cell entered, Cell previousWorker, int previousUse, int actionBase) {
            this.entered = entered;
            this.previousWorker = previousWorker;
            this.previousUse = previousUse;
            this.actionBase = actionBase;
        }

        void releaseNavigation() {
            walk = null;
            entry = null;
            escape = null;
            walkTrace = null;
            entryTrace = null;
            escapeTrace = null;
            after = null;
            index = null;
        }
    }

    // Build this only from completed directed searches. Each frame discards the large index when
    // descending; only the bounded, ordered candidates for its current target survive a backtrack.
    private final class StanceIndex {
        private static final int WIDTH = 4;
        final Map<Cell, List<Cell>> buckets = new HashMap<>();
        private Iterator<Cell> pending;
        private Map<Cell, Cell> entry;

        StanceIndex(Map<Cell, Cell> walk, Map<Cell, Cell> entry) {
            pending = walk.keySet().iterator();
            this.entry = entry;
        }

        boolean advance(Budget budget) {
            while (pending != null) {
                if (!budget.tryStep()) return false;
                if (pending.hasNext()) {
                    Cell stance = pending.next();
                    if (entry.containsKey(stance)) {
                        buckets.computeIfAbsent(bucket(stance), ignored -> new ArrayList<>()).add(stance);
                        indexedStances++;
                    }
                } else {
                    pending = null;
                    entry = null;
                }
            }
            return true;
        }

        private static Cell bucket(Cell cell) {
            return new Cell(Math.floorDiv(cell.x(), WIDTH), Math.floorDiv(cell.y(), WIDTH),
                Math.floorDiv(cell.z(), WIDTH));
        }
    }

    private final class Candidates {
        final PriorityQueue<Cell> ready;
        private final Cell target;
        private final Cell min;
        private final Cell max;
        private int x;
        private int y;
        private int z;
        private List<Cell> bucket;
        private int at;
        private boolean done;

        Candidates(Cell target) {
            this.target = target;
            ready = new PriorityQueue<>(this::compareArrival);
            // Both reach tests bound every coordinate by ceil(reach). This query is a superset;
            // the exact local rule, including sight and the actual body, still decides each Place.
            int radius = (int) Math.ceil(problem.capability().workReach());
            min = StanceIndex.bucket(target.offset(-radius, -radius, -radius));
            max = StanceIndex.bucket(target.offset(radius, radius, radius));
            x = min.x();
            y = min.y();
            z = min.z();
        }

        boolean advance(Budget budget, StanceIndex index) {
            while (!done) {
                if (!budget.tryStep()) return false;
                if (bucket != null && at < bucket.size()) {
                    Cell stance = bucket.get(at++);
                    candidateStances++;
                    if (withinReach(stance)) ready.add(stance);
                } else if (x > max.x()) {
                    bucket = null;
                    done = true;
                } else {
                    bucket = index.buckets.getOrDefault(new Cell(x, y, z), List.of());
                    candidateBuckets++;
                    at = 0;
                    if (z < max.z()) z++;
                    else if (y < max.y()) { z = min.z(); y++; }
                    else { z = min.z(); y = min.y(); x++; }
                }
            }
            return true;
        }

        private boolean withinReach(Cell stance) {
            double dx = (double) target.x() - stance.x();
            double dy = target.y() + 0.5D - stance.y();
            double dz = (double) target.z() - stance.z();
            double eye = ConstructionRules.eyeHeight(problem.capability());
            double reach = problem.capability().workReach();
            return dx * dx + dy * dy + dz * dz <= reach * reach
                && dx * dx + (dy - eye) * (dy - eye) + dz * dz <= reach * reach;
        }

        private int compareArrival(Cell left, Cell right) {
            double a = arrivalDistanceSquared(left);
            double b = arrivalDistanceSquared(right);
            int order = Double.compare(a, b);
            return order == 0 ? left.compareTo(right) : order;
        }

        private double arrivalDistanceSquared(Cell stance) {
            double dx = (double) target.x() - stance.x();
            double dy = target.y() + 0.5D - stance.y();
            double dz = (double) target.z() - stance.z();
            double horizontal = Math.sqrt(dx * dx + dz * dz) + 0.9D;
            // Core accepts arrival within 0.9 horizontally and 1 vertically. Prefer a stance whose
            // entire arrival envelope leaves the feet and any eye inside this body in work reach.
            // This is only an order: center-only legal stances remain enumerable, and runtime still
            // checks the actual eye, sight, collision and directed escape before every real write.
            double vertical = Math.max(Math.abs(dy), Math.abs(dy - problem.capability().height())) + 1.0D;
            return horizontal * horizontal + vertical * vertical;
        }
    }

    private final class Navigation {
        final State terrain;
        final Iterator<Cell> roots;
        final Predicate<Cell> goal;
        final Map<Cell, Cell> parents = new HashMap<>();
        final Map<Cell, Integer> distances;
        final ArrayDeque<Cell> open = new ArrayDeque<>();
        Iterator<Cell> neighbors;
        Cell current;
        Cell found;
        boolean rootsDone;
        boolean done;

        Navigation(State terrain, Collection<Cell> roots, Predicate<Cell> goal) {
            this(terrain, roots, goal, false);
        }

        Navigation(State terrain, Collection<Cell> roots, Predicate<Cell> goal, boolean recordDistances) {
            this.terrain = terrain;
            this.roots = roots.iterator();
            this.goal = goal;
            distances = recordDistances ? new HashMap<>() : null;
        }

        boolean advance(Budget budget) {
            if (!geometry.advance(budget)) return false;
            while (!done && !budget.exhausted()) {
                if (!budget.tryStep()) return false;
                if (!rootsDone) {
                    if (roots.hasNext()) {
                        Cell root = roots.next();
                        if (ConstructionRules.standable(problem, terrain, root) && parents.putIfAbsent(root, root) == null) {
                            open.add(root);
                            if (distances != null) distances.put(root, 0);
                        }
                    } else rootsDone = true;
                } else if (neighbors != null && neighbors.hasNext()) {
                    Cell next = neighbors.next();
                    if (parents.putIfAbsent(next, current) == null) {
                        open.add(next);
                        if (distances != null) distances.put(next, distances.get(current) + 1);
                    }
                } else if (!open.isEmpty()) {
                    current = open.removeFirst();
                    if (goal != null && goal.test(current)) {
                        found = current;
                        done = true;
                    } else neighbors = geometry.moves(terrain, current).iterator();
                } else done = true;
            }
            return done;
        }
    }

    // This cache belongs to one immutable Problem and is never used by either verifier. With width
    // <=1, height<=2, step<=1, drop<=3 and the one-cell UNKNOWN halo, a directed edge reads no voxel
    // beyond horizontal distance 2 and vertical [-4,+3] from its origin. The larger [3,6] invalidation
    // box therefore covers support, both body sweeps, and every directed predecessor. Both tentative
    // placement and undo are registered before any navigation may query the changed terrain.
    private final class Geometry {
        private static final int MOST = 32768;
        private static final int ACROSS = 3;
        private static final int VERTICAL = 6;
        private static final int WIDTH = ACROSS * 2 + 1;
        private static final int HEIGHT = VERTICAL * 2 + 1;
        final Map<Cell, List<Cell>> moves = new LinkedHashMap<>();
        private final ArrayDeque<Cell> dirty = new ArrayDeque<>();
        private final Set<Cell> queued = new HashSet<>();
        private Cell changing;
        private int at;

        void invalidate(Cell cell) {
            if (queued.add(cell)) dirty.addLast(cell);
        }

        boolean advance(Budget budget) {
            while (changing != null || !dirty.isEmpty()) {
                if (!budget.tryStep()) return false;
                if (changing == null) {
                    changing = dirty.removeFirst();
                    at = 0;
                }
                int index = at++;
                try {
                    moves.remove(changing.offset(index / (WIDTH * HEIGHT) - ACROSS,
                        index / WIDTH % HEIGHT - VERTICAL, index % WIDTH - ACROSS));
                } catch (ArithmeticException outside) {
                    // A bounded model at an integer edge has no representable affected cache entry.
                }
                if (at == WIDTH * WIDTH * HEIGHT) {
                    queued.remove(changing);
                    changing = null;
                }
            }
            return true;
        }

        List<Cell> moves(State terrain, Cell from) {
            List<Cell> saved = moves.get(from);
            if (saved != null) return saved;
            List<Cell> result = ConstructionRules.moves(problem, terrain, from);
            moves.put(from, result);
            if (moves.size() > MOST) moves.remove(moves.keySet().iterator().next());
            return result;
        }
    }

    private static final class Trace {
        private final Map<Cell, Cell> parents;
        private final ArrayDeque<Cell> reverse = new ArrayDeque<>();
        private Cell cursor;
        private boolean done;
        private List<Cell> path;

        Trace(Map<Cell, Cell> parents, Cell cursor) {
            this.parents = parents;
            this.cursor = cursor;
        }

        boolean advance(Budget budget) {
            while (!done && budget.tryStep()) {
                reverse.addFirst(cursor);
                Cell previous = parents.get(cursor);
                if (previous == null) throw new IllegalStateException("a positive route lost its predecessor");
                if (previous.equals(cursor)) {
                    path = List.copyOf(reverse);
                    done = true;
                } else cursor = previous;
            }
            return done;
        }

        List<Cell> path() {
            if (!done) throw new IllegalStateException("unfinished route trace");
            return path;
        }
    }
}
