package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Registering;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;

public final class WaresPlugin {

    private WaresPlugin() {
    }

    public static void install(IEventBus modBus) {
        WaresBlockEntities.register(modBus);
        modBus.addListener(Declaring.class, WaresContent::declare);
        modBus.addListener(Registering.class, WaresContent::enroll);
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(WorkshopScene::declare);
        }
    }
}
