package io.github.izakyl.folkways.core.engine.travel;

import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Set;

public interface Ways {

    Faring journey(Resident who, WorldPos from, Set<WorldPos> goals);

    default Faring journey(Resident who, WorldPos from, Set<WorldPos> goals, Urgency urgency) {
        return journey(who, from, goals);
    }

    Faring anyoneReaches(Set<WorldPos> goals);

    // How long the journey would be, for weighing one against another: it lays out no legs, asks for no way to be
    // found and counts nothing as trodden. Later when the ways cannot say yet; `journey` is what asks.
    default Faring reckon(Resident who, WorldPos from, Set<WorldPos> goals) {
        return journey(who, from, goals, Urgency.PLANNING);
    }
}
