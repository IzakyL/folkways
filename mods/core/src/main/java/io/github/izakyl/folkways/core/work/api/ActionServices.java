package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import java.util.Set;

// Engine capabilities supplied through the holding context; the executor owns the action clock and body.
public interface ActionServices {

    Optional<Conveyance> approach(HoldingContext context, WorkAction action, Set<WorldPos> footings);

    void toolBroke(HoldingContext context, String item);

    RefusalKind noWay();

    RefusalKind travelFailure(RefusalKind why);

    RefusalKind broken();

    void failed(WorkAction action, Throwable failure);
}
