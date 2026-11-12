package io.github.izakyl.folkways.vanilla.patrol;

import io.github.izakyl.folkways.core.combat.api.Combatant;
import io.github.izakyl.folkways.core.equipment.api.EquipmentSuppliers;
import io.github.izakyl.folkways.core.equipment.api.Mannequins;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.ui.RegisteringUi;
import io.github.izakyl.folkways.vanilla.combat.WeaponStations;
import io.github.izakyl.folkways.vanilla.combat.VanillaCombatant;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutRegistry;
import io.github.izakyl.folkways.vanilla.combat.weapon.MountGear;
import io.github.izakyl.folkways.vanilla.combat.weapon.PatrolGear;
import io.github.izakyl.folkways.vanilla.combat.weapon.ResidentMounts;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.person.look.ResidentLook;
import io.github.izakyl.folkways.vanilla.person.look.ResidentLooks;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

public final class PatrolPlugin {

    private PatrolPlugin() {
    }

    public static void install(IEventBus modBus) {
        Combatant.install(new VanillaCombatant());
        LoadoutRegistry.install();
        MountGear.install();
        EquipmentSuppliers.register(PatrolGear.ID, new PatrolGear());
        EquipmentSuppliers.listen((colony, resident) ->
            PatrolContent.suppliesOf(colony).ifPresent(supplies -> supplies.invalidate(resident)));
        Mannequins.register(PatrolPlugin::mannequin);
        modBus.addListener(Declaring.class, PatrolContent::declare);
        modBus.addListener(Registering.class, PatrolContent::enroll);
        modBus.addListener(RegisteringUi.class, event -> {
            event.zoneSettings(List.of(PatrolContent.BEAT, SentryPosts.ZONE),
                (player, selected) -> new StaffingPanel(selected).build(player));
        });
        NeoForge.EVENT_BUS.register(Wards.class);
        NeoForge.EVENT_BUS.register(WeaponStations.class);
        NeoForge.EVENT_BUS.register(PatrolCombat.class);
        NeoForge.EVENT_BUS.addListener(ResidentMounts::died);
        NeoForge.EVENT_BUS.addListener(ResidentMounts::removed);
    }

    // The equipment page's figure: a resident wearing the pool's first skin, the same one every time it opens.
    private static LivingEntity mannequin(Level level) {
        ResidentEntity body = PersonBody.RESIDENT.get().create(level);
        if (body != null) {
            ResidentLooks.keysInPool(level.registryAccess()).stream()
                .sorted(Comparator.comparing(key -> key.location().toString()))
                .filter(key -> ResidentLooks.entry(level.registryAccess(), key)
                    .filter(look -> look.appearance() instanceof ResidentLook.Skin).isPresent())
                .findFirst().ifPresent(body::setLookId);
        }
        return body;
    }
}
