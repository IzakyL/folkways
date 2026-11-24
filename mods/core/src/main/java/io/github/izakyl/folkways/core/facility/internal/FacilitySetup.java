package io.github.izakyl.folkways.core.facility.internal;

import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.facility.api.FacilityRidePassage;
import io.github.izakyl.folkways.core.facility.api.Lease;
import io.github.izakyl.folkways.core.facility.api.Deposit;
import io.github.izakyl.folkways.core.facility.api.MoveTo;
import io.github.izakyl.folkways.core.facility.api.Routines;
import io.github.izakyl.folkways.core.facility.api.WriteSequence;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.shell.neoforge.VanillaControls;
import io.github.izakyl.folkways.core.shell.neoforge.VanillaMechanisms;
import net.neoforged.bus.api.IEventBus;

/** Core's facilities: vanilla signals, capability ports, leases, the routine library and its refinement. */
public final class FacilitySetup {

    private FacilitySetup() {
    }

    public static void install(IEventBus modBus) {
        Lease.install();
        Facilities.install();
        FacilityDefinition.installGateProjection(FacilityGates::project);
        VanillaControls.install();
        WorldProbes.install();
        VanillaMechanisms.install();
        CapabilityPorts.install();
        Routines.register(new WriteAll());
        Routines.register(new WriteStages());
        Routines.register(new WriteSequence());
        Routines.register(new MoveTo());
        Routines.register(new Deposit());
        modBus.addListener(Declaring.class, event -> {
            event.passage(new GatePassage());
            event.passage(new FacilityRidePassage());
            event.colony(DefinedFacilities.ID, DefinedFacilities::install);
            event.refinement(new RoutineRefinement());
        });
    }
}
