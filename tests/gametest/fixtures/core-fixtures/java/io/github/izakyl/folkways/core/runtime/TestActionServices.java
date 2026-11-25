package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.ActionServices;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import java.util.Set;

public class TestActionServices implements ActionServices {
    public Optional<Conveyance> approach(HoldingContext context, WorkAction action, Set<WorldPos> footings) {
        return Optional.empty();
    }

    public void toolBroke(HoldingContext context, String item) {}

    public RefusalKind noWay() {
        return LaborRefusal.NO_WAY_THERE;
    }

    public RefusalKind travelFailure(RefusalKind why) {
        return why;
    }

    public RefusalKind broken() {
        return LaborRefusal.WORK_BROKE;
    }

    public void failed(WorkAction action, Throwable failure) {
        throw new AssertionError(failure);
    }
}
