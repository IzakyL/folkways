package io.github.izakyl.folkways.plugins.groundwork.plan;

import java.util.AbstractList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;

/** Immutable inputs and complete evidence for the explicitly bounded voxel construction domain. */
public final class Construction {

    public static final long RULE_VERSION = 1L;
    public static final int MAX_CELLS = 131072;
    public static final int MAX_TARGETS = 32768;
    public static final int MAX_ACTIONS = 1000000;

    private Construction() {
    }

    public interface Budget {
        boolean tryStep();

        boolean exhausted();

        int used();
    }

    public record Cell(int x, int y, int z) implements Comparable<Cell> {
        public Cell offset(int dx, int dy, int dz) {
            return new Cell(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
        }

        public Cell above() {
            return offset(0, 1, 0);
        }

        public Cell below() {
            return offset(0, -1, 0);
        }

        @Override
        public int hashCode() {
            int mixed = x * 0x9E3779B9;
            mixed = Integer.rotateLeft(mixed, 13) ^ y * 0x85EBCA6B;
            mixed = Integer.rotateLeft(mixed, 11) ^ z * 0xC2B2AE35;
            mixed ^= mixed >>> 16;
            mixed *= 0x7FEB352D;
            mixed ^= mixed >>> 15;
            mixed *= 0x846CA68B;
            return mixed ^ mixed >>> 16;
        }

        @Override
        public int compareTo(Cell other) {
            int order = Integer.compare(x, other.x);
            if (order == 0) order = Integer.compare(y, other.y);
            return order == 0 ? Integer.compare(z, other.z) : order;
        }
    }

    public record Box(Cell min, Cell max) {
        public Box {
            Objects.requireNonNull(min);
            Objects.requireNonNull(max);
            if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
                throw new IllegalArgumentException("inverted construction bounds");
            }
            if (min.x() < Integer.MIN_VALUE + 16 || min.y() < Integer.MIN_VALUE + 16
                    || min.z() < Integer.MIN_VALUE + 16 || max.x() > Integer.MAX_VALUE - 16
                    || max.y() > Integer.MAX_VALUE - 16 || max.z() > Integer.MAX_VALUE - 16) {
                throw new IllegalArgumentException("construction bounds need coordinate arithmetic padding");
            }
        }

        public boolean contains(Cell cell) {
            return cell.x() >= min.x() && cell.x() <= max.x()
                && cell.y() >= min.y() && cell.y() <= max.y()
                && cell.z() >= min.z() && cell.z() <= max.z();
        }

        public long volume() {
            return Math.multiplyExact(Math.multiplyExact((long) max.x() - min.x() + 1L,
                (long) max.y() - min.y() + 1L), (long) max.z() - min.z() + 1L);
        }
    }

    /** UNKNOWN is neither passable air nor usable support, and is not real-world impossibility evidence. */
    public enum Voxel {
        AIR, FULL, UNKNOWN
    }

    public record Capability(double width, double height, int stepUp, int drop, double workReach) {
        public Capability {
            if (!Double.isFinite(width) || !Double.isFinite(height) || !Double.isFinite(workReach)
                    || width <= 0.0D || height <= 0.0D || stepUp < 0 || drop < 0 || workReach <= 0.0D) {
                throw new IllegalArgumentException("invalid construction capability");
            }
        }
    }

    public record Problem(Box bounds, Map<Cell, Voxel> baseVoxels, List<Cell> orderedTargets,
            Set<Cell> protectedCells, Set<Cell> safeCells, Cell start, int materialCount,
            Capability capability, long ruleVersion) {
        public Problem {
            Objects.requireNonNull(bounds);
            Objects.requireNonNull(start);
            Objects.requireNonNull(capability);
            if (bounds.volume() > MAX_CELLS || baseVoxels.size() > MAX_CELLS
                    || orderedTargets.size() > MAX_TARGETS || materialCount < 0) {
                throw new IllegalArgumentException("construction input exceeds the bounded domain");
            }
            baseVoxels = Map.copyOf(baseVoxels);
            orderedTargets = List.copyOf(orderedTargets);
            protectedCells = Set.copyOf(protectedCells);
            safeCells = Set.copyOf(safeCells);
            Set<Cell> targets = new LinkedHashSet<>(orderedTargets);
            if (!bounds.contains(start) || targets.size() != orderedTargets.size()
                    || baseVoxels.keySet().stream().anyMatch(cell -> !bounds.contains(cell))
                    || targets.stream().anyMatch(cell -> !bounds.contains(cell))
                    || protectedCells.stream().anyMatch(cell -> !bounds.contains(cell))
                    || safeCells.stream().anyMatch(cell -> !bounds.contains(cell) || targets.contains(cell))) {
                throw new IllegalArgumentException("construction cells lie outside their declared domain");
            }
            orderedTargets = new Targets(orderedTargets, Set.copyOf(targets));
        }

        public Voxel voxel(Cell cell) {
            return bounds.contains(cell) ? baseVoxels.getOrDefault(cell, Voxel.UNKNOWN) : Voxel.UNKNOWN;
        }

        public State initialState() {
            return new State(Set.of(), start, 0);
        }
    }

    // Target membership is local-rule input; it must not scan a large ordered target list on every edge.
    private static final class Targets extends AbstractList<Cell> implements RandomAccess {
        private final List<Cell> ordered;
        private final Set<Cell> members;

        Targets(List<Cell> ordered, Set<Cell> members) {
            this.ordered = ordered;
            this.members = members;
        }

        @Override
        public Cell get(int index) { return ordered.get(index); }

        @Override
        public int size() { return ordered.size(); }

        @Override
        public boolean contains(Object value) { return members.contains(value); }
    }

    public record State(Set<Cell> placed, Cell workerCell, int usedMaterials) {
        public State {
            placed = Set.copyOf(placed);
            Objects.requireNonNull(workerCell);
            if (usedMaterials < 0) throw new IllegalArgumentException("negative material use");
        }
    }

    public sealed interface Action permits Walk, Place, Finish {
    }

    /** Includes its starting and ending feet cells. Consecutive pairs are directed movement edges. */
    public record Walk(List<Cell> path) implements Action {
        public Walk {
            path = List.copyOf(path);
            if (path.size() < 2 || path.size() > MAX_CELLS) {
                throw new IllegalArgumentException("a walk needs a bounded complete path");
            }
        }
    }

    /** The two evidence paths do not move the worker: entry is before placement; escape is after it. */
    public record Place(Cell targetCell, Cell stance, List<Cell> entryFromSafe, List<Cell> escapeToSafe)
            implements Action {
        public Place {
            Objects.requireNonNull(targetCell);
            Objects.requireNonNull(stance);
            entryFromSafe = List.copyOf(entryFromSafe);
            escapeToSafe = List.copyOf(escapeToSafe);
            if (entryFromSafe.isEmpty() || escapeToSafe.isEmpty()
                    || entryFromSafe.size() > MAX_CELLS || escapeToSafe.size() > MAX_CELLS) {
                throw new IllegalArgumentException("placement needs bounded entry and immediate escape evidence");
            }
        }
    }

    public record Finish(Cell safeCell) implements Action {
        public Finish {
            Objects.requireNonNull(safeCell);
        }
    }

    public record Summary(State finalState, int remainingMaterials, boolean finished) {
        public Summary {
            Objects.requireNonNull(finalState);
            if (remainingMaterials < 0) throw new IllegalArgumentException("negative remaining material");
        }
    }

    /** Dependencies cover every input read required to replay this witness; the whole box is conservative. */
    public record Witness(List<Action> actions, Set<Cell> dependencies, String inputFingerprint,
            Summary summary) {
        public Witness {
            actions = List.copyOf(actions);
            dependencies = Set.copyOf(dependencies);
            Objects.requireNonNull(inputFingerprint);
            Objects.requireNonNull(summary);
            if (actions.size() > MAX_ACTIONS || dependencies.size() > MAX_CELLS) {
                throw new IllegalArgumentException("construction witness exceeds the bounded domain");
            }
        }
    }
}
