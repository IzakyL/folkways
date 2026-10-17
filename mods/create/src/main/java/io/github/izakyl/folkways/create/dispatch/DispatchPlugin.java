package io.github.izakyl.folkways.create.dispatch;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Registering;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;

public final class DispatchPlugin {

    private DispatchPlugin() {
    }

    public static void install(IEventBus modBus) {
        modBus.addListener(Declaring.class, DispatchContent::declare);
        modBus.addListener(Registering.class, DispatchContent::enroll);
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(DispatchScene::declare);
        }
    }
}
