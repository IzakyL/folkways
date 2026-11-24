package io.github.izakyl.folkways.core.command.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.command.combat.command.ResidentCommands;
import io.github.izakyl.folkways.core.command.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.core.command.combat.group.UnitGroups;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Transfers direct resident control to a vehicle seat, preserving transported squads. */
public final class CrewSelection {
    private CrewSelection() {
    }

    public static boolean commanded(Colony colony, UUID resident) {
        return CombatSelectionOwnership.of(colony).reserved(resident)
            || ResidentCommands.of(colony).current(resident).isPresent();
    }

    public static void trackVehicle(Colony colony, ServerPlayer player, Entity vehicle, Vehicles.Order order) {
        CombatSelectionOwnership.of(colony).trackVehicle(player, vehicle, order);
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
