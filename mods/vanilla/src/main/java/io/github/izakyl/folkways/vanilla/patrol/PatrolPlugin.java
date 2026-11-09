package io.github.izakyl.folkways.vanilla.patrol;

import io.github.izakyl.folkways.core.combat.api.Combatant;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.ui.RegisteringUi;
import io.github.izakyl.folkways.vanilla.combat.WeaponStations;
import io.github.izakyl.folkways.vanilla.combat.VanillaCombatant;
import io.github.izakyl.folkways.vanilla.combat.ui.LoadoutContent;
import io.github.izakyl.folkways.vanilla.combat.ui.LoadoutPage;
import io.github.izakyl.folkways.vanilla.combat.weapon.ResidentMounts;
import java.util.List;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

public final class PatrolPlugin {

    private PatrolPlugin() {
    }

    public static void install(IEventBus modBus) {
        Combatant.install(new VanillaCombatant());
        modBus.addListener(Declaring.class, PatrolContent::declare);
        modBus.addListener(Registering.class, PatrolContent::enroll);
        modBus.addListener(RegisteringUi.class, event -> {
            event.page(LoadoutContent.ID, LoadoutPage::new);
            event.zoneSettings(List.of(PatrolContent.BEAT, SentryPosts.ZONE),
                (player, selected) -> new StaffingPanel(selected).build(player));
        });
        NeoForge.EVENT_BUS.register(Wards.class);
        NeoForge.EVENT_BUS.register(WeaponStations.class);
        NeoForge.EVENT_BUS.register(PatrolCombat.class);
        NeoForge.EVENT_BUS.addListener(ResidentMounts::died);
        NeoForge.EVENT_BUS.addListener(ResidentMounts::removed);
    }
}
