package io.github.izakyl.folkways.vanilla.warfare;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.shell.warfare.WarfareAirfields;
import io.github.izakyl.folkways.core.shell.warfare.WarfareVehicles;
import io.github.izakyl.folkways.core.shell.warfare.WarfareWeapons;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

public final class WarfareResidentIntegration {
    private WarfareResidentIntegration() {
    }

    public static void install(IEventBus bus) {
        if (FMLEnvironment.dist.isClient()) {
            WarfareClient.install();
        }
        WarfareVehicles.gameplay(new WarfareCrew());
        WarfareAirfields.install(new VehicleAirports());
        bus.addListener(Declaring.class, event -> WarfareWeapons.register(WarfareRoster.ALL));
        bus.addListener(Declaring.class, WarfareCrew::declare);
        bus.addListener(Declaring.class, WarfareStations::declare);
        bus.addListener(Registering.class, WarfareStations::enroll);
        NeoForge.EVENT_BUS.addListener(WarfareStations::left);
    }
}
