package io.github.izakyl.folkways.vanilla.fishing;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Registering;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;

public final class FishingPlugin {

    private FishingPlugin() {
    }

    public static void install(IEventBus modBus) {
        modBus.addListener(Declaring.class, FishingContent::declare);
        modBus.addListener(Registering.class, FishingContent::enroll);
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(FishingScene::declare);
        }
    }
}
