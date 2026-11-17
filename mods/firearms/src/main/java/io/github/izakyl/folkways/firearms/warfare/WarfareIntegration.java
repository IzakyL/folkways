package io.github.izakyl.folkways.firearms.warfare;

import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
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
        Vehicles.register(WarfareVehicles.INSTANCE);
        LandingSites.registerFacilities(VehicleCatapults::scan);
        bus.addListener(Declaring.class, WarfareVehicles::declare);
        NeoForge.EVENT_BUS.addListener(WarfareVehicles::tick);
        NeoForge.EVENT_BUS.addListener(WarfareVehicles::left);
        bus.addListener(Declaring.class, WarfareWeapons::declare);
        bus.addListener(Declaring.class, WarfareStations::declare);
        bus.addListener(Registering.class, WarfareStations::enroll);
        NeoForge.EVENT_BUS.addListener(WarfareWeapons::ticked);
        NeoForge.EVENT_BUS.addListener(WarfareStations::left);
        NeoForge.EVENT_BUS.addListener(WarfareExplosions::joined);
    }
}
