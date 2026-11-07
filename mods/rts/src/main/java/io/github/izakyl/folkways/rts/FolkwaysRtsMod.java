package io.github.izakyl.folkways.rts;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.rts.combat.command.CommandContent;
import io.github.izakyl.folkways.rts.combat.command.CommandDefenseControl;
import io.github.izakyl.folkways.rts.combat.control.SelectionControlContent;
import io.github.izakyl.folkways.rts.combat.group.GroupContent;
import io.github.izakyl.folkways.rts.combat.ui.CombatContent;
import io.github.izakyl.folkways.rts.upgrade.RtsUpgrades;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/** Optional resident commands, squads and RTS interaction. */
@Mod(FolkwaysRtsMod.MOD_ID)
public final class FolkwaysRtsMod {
    public static final String MOD_ID = "folkways_rts";

    public FolkwaysRtsMod(IEventBus modBus) {
        RtsUpgrades.register();
        modBus.addListener(Declaring.class, CommandContent::declare);
        modBus.addListener(Declaring.class, GroupContent::declare);
        modBus.addListener(Declaring.class, SelectionControlContent::declare);
        modBus.addListener(Declaring.class, CommandDefenseControl::declare);
        CombatContent.install(modBus);
    }
}
