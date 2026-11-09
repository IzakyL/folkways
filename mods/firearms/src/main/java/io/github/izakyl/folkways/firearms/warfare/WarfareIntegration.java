package io.github.izakyl.folkways.firearms.warfare;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Registering;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

public final class WarfareIntegration {
    private WarfareIntegration() {
    }

    public static void install(IEventBus bus) {
        if (FMLEnvironment.dist.isClient()) {
            WarfareClient.install();
        }
        bus.addListener(Declaring.class, WarfareWeapons::declare);
        bus.addListener(Declaring.class, WarfareStations::declare);
        bus.addListener(Registering.class, WarfareStations::enroll);
        NeoForge.EVENT_BUS.addListener(WarfareWeapons::ticked);
        NeoForge.EVENT_BUS.addListener(WarfareStations::left);
        NeoForge.EVENT_BUS.addListener(WarfareExplosions::joined);
    }
}
