package io.github.izakyl.folkways.core.rts.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.rts.combat.command.ResidentCommands;
import io.github.izakyl.folkways.core.rts.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.core.rts.combat.group.UnitGroups;
import java.util.UUID;

/** Transfers direct resident control to a vehicle seat, preserving transported squads. */
public final class VehicleCrewCommands {
    private VehicleCrewCommands() {
    }

    public static boolean commanded(Colony colony, UUID resident) {
        return CombatSelectionOwnership.of(colony).reserved(resident)
            || ResidentCommands.of(colony).current(resident).isPresent();
    }

    public static void board(Colony colony, UUID resident, boolean passenger) {
        CombatSelectionOwnership.of(colony).releaseResident(resident);
        ResidentCommands.of(colony).current(resident).ifPresent(order -> order.close());
        UnitGroups.of(colony).ofResident(resident).ifPresent(group -> {
            if (passenger) {
                group.release();
            } else {
                group.remove(resident);
            }
        });
    }
}
