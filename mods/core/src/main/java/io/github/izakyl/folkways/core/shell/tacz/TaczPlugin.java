package io.github.izakyl.folkways.core.shell.tacz;

import com.tacz.guns.entity.EntityKineticBullet;
import io.github.izakyl.folkways.core.combat.api.ColonyDamage;
import io.github.izakyl.folkways.core.combat.api.Loadouts;
import io.github.izakyl.folkways.core.combat.api.Weapons;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

final class TaczPlugin {

    private TaczPlugin() {
    }

    static void install(IEventBus modBus) {
        ColonyDamage.registerSource(entity -> entity instanceof EntityKineticBullet bullet ? bullet.getOwner() : null);
        NeoForge.EVENT_BUS.addListener(
            (EntityJoinLevelEvent event) -> {
                if (!event.getLevel().isClientSide() && event.getEntity() instanceof EntityKineticBullet bullet) {
                    ColonyDamage.party(bullet.getOwner()).ifPresent(id -> ColonyDamage.mark(bullet, id));
                }
            });
        modBus.addListener(
            (FMLCommonSetupEvent event) ->
                event.enqueueWork(
                    () -> {
                        Weapons.register(TaczLoadouts.ID, new TaczWeaponAdapter());
                        Loadouts.register(TaczLoadouts.ID, new TaczLoadouts());
                    }));
    }
}
