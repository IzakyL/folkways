package io.github.izakyl.folkways.core.rts;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.rts.combat.command.CommandContent;
import io.github.izakyl.folkways.core.rts.combat.command.CommandDefenseControl;
import io.github.izakyl.folkways.core.rts.combat.control.SelectionControlContent;
import io.github.izakyl.folkways.core.rts.combat.group.GroupContent;
import io.github.izakyl.folkways.core.rts.combat.ui.CombatContent;
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
    }
}
