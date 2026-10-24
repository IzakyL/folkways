package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Registering;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;

public final class FarmingPlugin {

    private FarmingPlugin() {
    }

    public static void install(IEventBus modBus) {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(CropCare::grown);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(CropCare::broken);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(CropCare::placed);
        modBus.addListener(Declaring.class, FarmingContent::declare);
        modBus.addListener(Registering.class, FarmingContent::enroll);
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(FarmingScene::declare);
        }
    }
}
