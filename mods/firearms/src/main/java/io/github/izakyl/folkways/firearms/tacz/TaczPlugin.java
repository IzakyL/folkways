package io.github.izakyl.folkways.firearms.tacz;

import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutRegistry;
import io.github.izakyl.folkways.vanilla.combat.weapon.Weapons;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;

final class TaczPlugin {

    private TaczPlugin() {
    }

    static void install(IEventBus modBus) {
        if (FMLEnvironment.dist.isClient()) {
            TaczClient.install();
        }
        modBus.addListener(
            (FMLCommonSetupEvent event) ->
                event.enqueueWork(
                    () -> {
                        Weapons.register(TaczLoadouts.ID, new TaczWeaponAdapter());
                        LoadoutRegistry.register(TaczLoadouts.ID, new TaczLoadouts());
                    }));
        modBus.addListener(
            (RegisterCapabilitiesEvent event) ->
                event.registerEntity(
                    Capabilities.ItemHandler.ENTITY,
                    PersonBody.RESIDENT.get(),
                    (body, context) -> new TaczAmmoInventory(body)));
        NeoForge.EVENT_BUS.addListener(TaczDischargeGuard::firing);
        NeoForge.EVENT_BUS.addListener(TaczDischargeGuard::joined);
        NeoForge.EVENT_BUS.addListener(TaczDischargeGuard::hurt);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, TaczDischargeGuard::hitBlock);
    }
}
