package io.github.izakyl.folkways.core.command;

import io.github.izakyl.folkways.core.card.net.CardSync;
import io.github.izakyl.folkways.core.combat.api.Commands;
import io.github.izakyl.folkways.core.command.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.core.colony.charter.CurrentColony;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.command.combat.command.CommandContent;
import io.github.izakyl.folkways.core.command.combat.command.CommandDefenseControl;
import io.github.izakyl.folkways.core.command.combat.control.SelectionControlContent;
import io.github.izakyl.folkways.core.command.combat.group.GroupContent;
import io.github.izakyl.folkways.core.command.combat.ui.CombatContent;
import net.neoforged.bus.api.IEventBus;

/** Resident commands, squads and RTS interaction installed by Vanilla. */
public final class RtsIntegration {
    private RtsIntegration() {
    }

    public static void install(IEventBus modBus) {
        modBus.addListener(Declaring.class, CommandContent::declare);
        modBus.addListener(Declaring.class, GroupContent::declare);
        modBus.addListener(Declaring.class, SelectionControlContent::declare);
        modBus.addListener(Declaring.class, CommandDefenseControl::declare);
        CombatContent.install(modBus);
        CardSync.looking(player -> CurrentColony.of(player)
            .flatMap(colony -> colony.service(Commands.SELECTION, CombatSelectionOwnership.class))
            .map(ownership -> ownership.selectedFacilities(player)).orElse(java.util.List.of()));
    }
}
