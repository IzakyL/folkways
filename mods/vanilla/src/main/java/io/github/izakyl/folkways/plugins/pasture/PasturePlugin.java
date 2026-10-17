package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Registering;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;

public final class PasturePlugin {

    private PasturePlugin() {
    }

    public static void install(IEventBus modBus) {
        modBus.addListener(Declaring.class, PastureContent::declare);
        modBus.addListener(Registering.class, PastureContent::enroll);
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(PastureScene::declare);
        }
    }
}
