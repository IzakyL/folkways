package io.github.izakyl.folkways.tacz;

import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutRegistry;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;

final class TaczPlugin {
    private TaczPlugin() { }
    static void install(IEventBus modBus) {
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) { TaczClient.install(); }
        modBus.addListener(io.github.izakyl.folkways.core.api.Declaring.class, event ->
            io.github.izakyl.folkways.front.api.CoreSettings.defaultTool(
                io.github.izakyl.folkways.core.api.terms.ItemSpec.of(com.tacz.guns.init.ModItems.MODERN_KINETIC_GUN.get())));
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            Weapons.register(TaczLoadouts.ID, new TaczWeaponAdapter());
            LoadoutRegistry.register(TaczLoadouts.ID, new TaczLoadouts());
        }));
        modBus.addListener((RegisterCapabilitiesEvent event) -> event.registerEntity(Capabilities.ItemHandler.ENTITY,
            PersonBody.RESIDENT.get(), (body, context) -> new TaczAmmoInventory(body)));
        NeoForge.EVENT_BUS.addListener(TaczDischargeGuard::firing);
        NeoForge.EVENT_BUS.addListener(TaczDischargeGuard::joined);
        NeoForge.EVENT_BUS.addListener(TaczDischargeGuard::hurt);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, TaczDischargeGuard::hitBlock);
    }
}
