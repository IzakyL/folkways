package io.github.izakyl.folkways.plugins.golem;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.plugins.golem.domain.GolemContent;
import io.github.izakyl.folkways.plugins.golem.resident.GolemKinds;
import io.github.izakyl.folkways.plugins.golem.resident.Golems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

public final class GolemPlugin {

    private GolemPlugin() {
    }

    public static void install(IEventBus modBus) {
        modBus.addListener(Declaring.class, event -> {
            Golems.install(NeoForge.EVENT_BUS);
            GolemContent.declare(event);
            GolemKinds.declare(event);
        });
        modBus.addListener(Registering.class, event -> {
            GolemContent.enroll(event);
            GolemKinds.enroll(event);
        });
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(GolemScene::declare);
        }
    }
}
