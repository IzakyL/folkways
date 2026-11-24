package io.github.izakyl.folkways.core.shell.epicfight;

import io.github.izakyl.folkways.core.combat.api.Loadouts;
import io.github.izakyl.folkways.core.combat.api.Weapons;
import io.github.izakyl.folkways.core.shell.epicfight.client.AnimationPairingReloadGuard;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

public final class EpicFightIntegration {
    public static final String ID = "folkways_core";

    private EpicFightIntegration() {
    }

    public static void install(IEventBus modBus) {
        modBus.addListener(EpicFightIntegration::setup);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NeoForge.EVENT_BUS.addListener(AnimationPairingReloadGuard::onLoggingOut);
        }
    }

    private static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ResourceLocation melee = ResourceLocation.fromNamespaceAndPath(ID, "melee");
            Weapons.register(melee, EpicFightWeaponAdapter.INSTANCE);
            Loadouts.register(melee, EpicFightLoadoutProvider.INSTANCE);
        });
    }
}
