package io.github.izakyl.folkways.core.facility.internal;

import io.github.izakyl.folkways.core.facility.api.Routines;
import io.github.izakyl.folkways.core.facility.api.Run;
import io.github.izakyl.folkways.core.facility.api.ScriptBatch;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

// Grows a routine's run into the routine's work. The plan asks this off the server thread: the routine plans from the
// snapshot the run carries.
final class RoutineRefinement implements Refinement {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "routine");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Optional<Change> rewrite(Intent intent, Grown graph) {
        if (intent instanceof ScriptBatch batch) {
            return Optional.of(new Change(batch.graph(), List.of(), List.of(), batch.holds()));
        }
        if (!(intent instanceof Run run)) {
            return Optional.empty();
        }
        return Routines.of(run.routine()).flatMap(routine -> routine.expand(run.call()));
    }
}
