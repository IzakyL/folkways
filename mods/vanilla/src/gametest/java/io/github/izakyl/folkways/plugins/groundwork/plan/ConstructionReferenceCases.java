package io.github.izakyl.folkways.plugins.groundwork.plan;

import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Box;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Capability;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Cell;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Problem;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Voxel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure fixture data shared by the independent JDK harness and GameTests, with no production rules. */
public final class ConstructionReferenceCases {
    public static final Capability WALKER = new Capability(0.6D, 1.8D, 1, 3, 4.5D);

    private ConstructionReferenceCases() {
    }

    public record Named(String name, Problem problem) {
    }

    public static List<Named> positive() {
        return List.of(new Named("raised-pit", raisedPit()), new Named("roof-from-below", roof()),
            new Named("single-mouth", singleMouth()), new Named("shared-branch", sharedBranch()),
            new Named("last-cell", lastCell()), new Named("two-cell-gate", twoCellGate()),
            new Named("directed-alternate-return", directedReturn(true)));
    }

    public static Problem raisedPit() {
        Fixture fixture = room(5, 9, 5).air(1, 1, 1, 3, 8, 3);
        return fixture.problem(List.of(new Cell(1, 1, 2), new Cell(2, 1, 2), new Cell(2, 2, 2), new Cell(2, 7, 2)),
            new Cell(1, 1, 1), Set.of(new Cell(1, 1, 1)), 4);
    }

    public static Problem roof() {
        Fixture fixture = room(5, 8, 5).air(1, 1, 1, 3, 7, 3);
        return fixture.problem(List.of(new Cell(2, 4, 2)), new Cell(1, 1, 1), Set.of(new Cell(1, 1, 1)), 1);
    }

    public static Problem singleMouth() {
        Fixture fixture = room(10, 4, 3).air(1, 1, 1, 8, 2, 1);
        return fixture.problem(List.of(new Cell(3, 1, 1), new Cell(7, 1, 1)),
            new Cell(1, 1, 1), Set.of(new Cell(1, 1, 1)), 2);
    }

    public static Problem sharedBranch() {
        Fixture fixture = room(7, 4, 7).air(1, 1, 2, 5, 2, 2).air(5, 1, 1, 5, 2, 5);
        return fixture.problem(List.of(new Cell(3, 1, 2), new Cell(5, 1, 1), new Cell(5, 1, 5)),
            new Cell(1, 1, 2), Set.of(new Cell(1, 1, 2)), 3);
    }

    public static Problem lastCell() {
        Fixture fixture = room(6, 4, 3).air(1, 1, 1, 4, 2, 1);
        return fixture.problem(List.of(new Cell(3, 1, 1)), new Cell(3, 1, 1), Set.of(new Cell(1, 1, 1)), 1);
    }

    public static Problem twoCellGate() {
        Fixture fixture = room(9, 4, 4).air(1, 1, 1, 7, 2, 2);
        return fixture.problem(List.of(new Cell(3, 1, 1), new Cell(3, 1, 2)),
            new Cell(5, 1, 1), Set.of(new Cell(1, 1, 1)), 2);
    }

    public static Problem directedReturn(boolean stairs) {
        Fixture fixture = room(7, 7, 6).air(1, 1, 1, 5, 6, 4)
            .full(1, 1, 1).full(1, 2, 1);
        if (stairs) fixture.full(2, 1, 3).full(1, 1, 3).full(1, 2, 3).full(1, 1, 2).full(1, 2, 2);
        return fixture.problem(List.of(), new Cell(2, 1, 1), Set.of(new Cell(1, 3, 1)), 0);
    }

    public static Problem eightTargets() {
        Fixture fixture = room(5, 7, 5).air(1, 1, 1, 3, 6, 3);
        List<Cell> targets = new ArrayList<>();
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                if (x != 2 || z != 2) targets.add(new Cell(x, 4, z));
            }
        }
        return fixture.problem(targets, new Cell(2, 1, 2), Set.of(new Cell(2, 1, 2)), 8);
    }

    public static Problem afterPrematureMouthClosure() {
        Problem before = singleMouth();
        Map<Cell, Voxel> voxels = new LinkedHashMap<>(before.baseVoxels());
        voxels.put(before.orderedTargets().getFirst(), Voxel.FULL);
        return new Problem(before.bounds(), voxels, List.of(before.orderedTargets().getLast()),
            before.protectedCells(), before.safeCells(), before.start(), 1, before.capability(), before.ruleVersion());
    }

    private static Fixture room(int maxX, int maxY, int maxZ) {
        return new Fixture(new Box(new Cell(-1, -1, -1), new Cell(maxX, maxY, maxZ)));
    }

    private static final class Fixture {
        final Box box;
        final Map<Cell, Voxel> voxels = new LinkedHashMap<>();

        Fixture(Box box) {
            this.box = box;
            for (int x = box.min().x(); x <= box.max().x(); x++) {
                for (int y = box.min().y(); y <= box.max().y(); y++) {
                    for (int z = box.min().z(); z <= box.max().z(); z++) voxels.put(new Cell(x, y, z), Voxel.FULL);
                }
            }
        }

        Fixture air(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) voxels.put(new Cell(x, y, z), Voxel.AIR);
                }
            }
            return this;
        }

        Fixture full(int x, int y, int z) {
            voxels.put(new Cell(x, y, z), Voxel.FULL);
            return this;
        }

        Problem problem(List<Cell> targets, Cell start, Set<Cell> safe, int materials) {
            return new Problem(box, voxels, targets, Set.of(), safe, start, materials, WALKER, Construction.RULE_VERSION);
        }
    }
}
