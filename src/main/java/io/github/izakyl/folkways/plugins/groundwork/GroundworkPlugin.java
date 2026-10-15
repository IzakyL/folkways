package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Registering;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;

public final class GroundworkPlugin {

    private GroundworkPlugin() {
    }

    public static void install(IEventBus modBus) {
        modBus.addListener(Declaring.class, GroundworkContent::declare);
        modBus.addListener(Registering.class, GroundworkContent::enroll);
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(GroundworkScene::declare);
        }
    }
}
