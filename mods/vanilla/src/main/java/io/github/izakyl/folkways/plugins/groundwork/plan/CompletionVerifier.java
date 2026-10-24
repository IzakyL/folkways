package io.github.izakyl.folkways.plugins.groundwork.plan;

import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Action;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Budget;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Cell;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Finish;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Place;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Problem;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.State;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Walk;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Witness;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Replays complete evidence independently of the planner. It never searches for an omitted path. */
public final class CompletionVerifier {
    public enum Status { VERIFYING, VALID, INVALID }

    private final Problem problem;
    private final Witness witness;
    private final ConstructionRules.Fingerprint fingerprint;
    private final Set<Cell> targets = new HashSet<>();
    private State state;
    private Status status = Status.VERIFYING;
    private String reason = "verifying complete construction evidence";
    private int stage;
    private int target;
    private int x;
    private int y;
    private int z;
    private int actionIndex;
    private int edge;
    private List<Cell> path;
    private Place placing;
    private boolean finished;

    public CompletionVerifier(Problem problem, Witness witness) {
        this.problem = Objects.requireNonNull(problem);
        this.witness = Objects.requireNonNull(witness);
        fingerprint = new ConstructionRules.Fingerprint(problem);
        state = problem.initialState();
        x = problem.bounds().min().x();
        y = problem.bounds().min().y();
        z = problem.bounds().min().z();
    }

    public Status status() { return status; }
    public String reason() { return reason; }
    public int actionIndex() { return actionIndex; }
    public State state() { return state; }

    public Status advance(Budget budget) {
        while (status == Status.VERIFYING && !budget.exhausted()) {
            switch (stage) {
                case 0 -> {
                    if (!budget.tryStep()) return status;
                    if (!ConstructionRules.supported(problem)) return invalid("unsupported construction rules or material domain");
                    if (!ConstructionRules.standable(problem, state, state.workerCell())) return invalid("the initial worker footing is not legal");
                    if (witness.dependencies().size() != problem.bounds().volume()) return invalid("v1 evidence must cover its whole finite snapshot");
                    stage++;
                }
                case 1 -> {
                    if (!fingerprint.advance(budget)) return status;
                    if (!fingerprint.value().orElseThrow().equals(witness.inputFingerprint())) return invalid("input fingerprint differs");
                    stage++;
                }
                case 2 -> {
                    if (!budget.tryStep()) return status;
                    if (!witness.dependencies().contains(new Cell(x, y, z))) return invalid("a snapshot dependency is missing");
                    if (z < problem.bounds().max().z()) z++;
                    else if (y < problem.bounds().max().y()) { z = problem.bounds().min().z(); y++; }
                    else if (x < problem.bounds().max().x()) { z = problem.bounds().min().z(); y = problem.bounds().min().y(); x++; }
                    else stage++;
                }
                case 3 -> {
                    if (!budget.tryStep()) return status;
                    if (target < problem.orderedTargets().size()) targets.add(problem.orderedTargets().get(target++));
                    else stage++;
                }
                case 4 -> {
                    if (!budget.tryStep()) return status;
                    if (actionIndex == witness.actions().size()) {
                        if (!finished) return invalid("the complete sequence has no final retreat and Finish");
                        if (!state.equals(witness.summary().finalState()) || !witness.summary().finished()
                                || witness.summary().remainingMaterials() != problem.materialCount() - state.usedMaterials()) {
                            return invalid("terminal state or material ledger differs from the replay");
                        }
                        status = Status.VALID;
                        reason = "the entire sequence fills every target and finishes at a stable safe cell";
                        return status;
                    }
                    if (finished) return invalid("an action occurs after Finish");
                    Action action = witness.actions().get(actionIndex);
                    if (action instanceof Walk walk) {
                        path = walk.path();
                        if (!path.getFirst().equals(state.workerCell())) return invalid("Walk does not start at the current worker");
                        edge = 1;
                        stage = 5;
                    } else if (action instanceof Place place) {
                        placing = place;
                        if (!place.stance().equals(state.workerCell()) || !targets.contains(place.targetCell())
                                || state.placed().contains(place.targetCell())
                                || !ConstructionRules.canPlaceLocal(problem, state, place.targetCell(), place.stance())) {
                            return invalid("Place has no legal current target, material or stance");
                        }
                        path = place.entryFromSafe();
                        if (!problem.safeCells().contains(path.getFirst()) || !path.getLast().equals(place.stance())) {
                            return invalid("entry evidence is not a directed stable-safe-to-stance path");
                        }
                        edge = 0;
                        stage = 6;
                    } else if (action instanceof Finish finish) {
                        if (!finish.safeCell().equals(state.workerCell()) || !problem.safeCells().contains(finish.safeCell())
                                || !ConstructionRules.standable(problem, state, finish.safeCell())
                                || state.placed().size() != targets.size() || state.usedMaterials() != targets.size()) {
                            return invalid("Finish is not complete, safe, or materially balanced");
                        }
                        target = 0;
                        stage = 8;
                    }
                }
                case 5 -> {
                    if (!budget.tryStep()) return status;
                    if (edge < path.size()) {
                        if (!ConstructionRules.legalMove(problem, state, path.get(edge - 1), path.get(edge))) return invalid("Walk contains an illegal directed edge");
                        edge++;
                    } else {
                        state = new State(state.placed(), path.getLast(), state.usedMaterials());
                        actionIndex++;
                        stage = 4;
                    }
                }
                case 6, 7 -> {
                    if (!budget.tryStep()) return status;
                    if (edge < path.size()) {
                        if (!ConstructionRules.standable(problem, state, path.get(edge))
                                || edge > 0 && !ConstructionRules.legalMove(problem, state, path.get(edge - 1), path.get(edge))) {
                            return invalid(stage == 6 ? "entry evidence is invalid before placement" : "escape evidence is invalid after placement");
                        }
                        edge++;
                    } else if (stage == 6) {
                        state = ConstructionRules.applyLocal(problem, state, placing);
                        path = placing.escapeToSafe();
                        if (!path.getFirst().equals(placing.stance()) || !problem.safeCells().contains(path.getLast())) {
                            return invalid("escape evidence is not a directed stance-to-stable-safe path");
                        }
                        edge = 0;
                        stage = 7;
                    } else {
                        // Neither evidence path moves the builder. Only an actual Walk changes its pose.
                        actionIndex++;
                        stage = 4;
                    }
                }
                case 8 -> {
                    if (!budget.tryStep()) return status;
                    if (target < problem.orderedTargets().size()) {
                        if (!state.placed().contains(problem.orderedTargets().get(target++))) return invalid("a target is missing from the final state");
                    } else {
                        finished = true;
                        actionIndex++;
                        stage = 4;
                    }
                }
                default -> throw new IllegalStateException("unknown construction replay stage");
            }
        }
        return status;
    }

    private Status invalid(String message) {
        status = Status.INVALID;
        reason = message;
        return status;
    }
}
