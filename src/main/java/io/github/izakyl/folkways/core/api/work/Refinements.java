package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.Ledger;
import java.util.List;

public final class Refinements {
    private static final Ledger<Refinement> RULES = new Ledger<>("a refinement", Refinement::id);
    private Refinements() { }
    public static void register(Refinement rule) { RULES.claim(rule); }
    public static List<Refinement> all() { return RULES.all(); }
}
