package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.base.Ledger;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.work.api.Hold;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Refinements {
    private static final Ledger<Refinement> RULES = new Ledger<>("a refinement", Refinement::id);
    private Refinements() { }

    public static void register(Refinement rule) { RULES.claim(rule); }

    public static List<Refinement> all() { return RULES.all(); }

    /** Refines workshop work before a caller decorates its concrete nodes for execution. */
    public static Optional<Grown> expand(Grown work) {
        return new Refining(all()).expand(work).map(Refining.Result::graph);
    }

    /** Keeps the workshop's reservations alongside reservations introduced while refining its work. */
    public static Optional<Refinement.Change> expand(Refinement.Change work) {
        return new Refining(all()).expand(work.replacement()).map(result -> {
            List<Hold> holds = new ArrayList<>(work.holds());
            holds.addAll(result.holds());
            return new Refinement.Change(result.graph(), work.links(), work.workerGroups(), holds);
        });
    }
}
