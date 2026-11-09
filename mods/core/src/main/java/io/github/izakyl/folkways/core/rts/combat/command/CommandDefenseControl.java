package io.github.izakyl.folkways.core.rts.combat.command;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.combat.api.DefenseControl;
import io.github.izakyl.folkways.core.rts.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.core.rts.combat.group.UnitGroup;
import io.github.izakyl.folkways.core.rts.combat.group.UnitGroups;
import java.util.Optional;
import java.util.UUID;

/** Keeps manual-control implementation details out of the autonomous defence services. */
public record CommandDefenseControl(Colony colony) implements DefenseControl {
    public static void declare(Declaring declaring) {
        declaring.colony(DefenseControl.ID, scope -> scope.share(new CommandDefenseControl(scope.colony())));
    }

    public Optional<ResidentCommands.Order> current(UUID resident) {
        return ResidentCommands.of(colony).current(resident);
    }

    public boolean reserved(UUID resident) {
        return CombatSelectionOwnership.of(colony).reserved(resident);
    }

    public boolean failedWaiting(UUID resident) {
        return UnitGroups.of(colony)
            .ofResident(resident)
            .filter(group -> group.phase() == UnitGroup.Phase.FAILED_WAITING)
            .isPresent();
    }
}
