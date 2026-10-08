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
}
