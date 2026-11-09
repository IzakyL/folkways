package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.base.Ledger;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import java.util.List;

public final class Refinements {
    private static final Ledger<Refinement> RULES = new Ledger<>("a refinement", Refinement::id);
    private Refinements() { }
    public static void register(Refinement rule) { RULES.claim(rule); }
    public static List<Refinement> all() { return RULES.all(); }
}
