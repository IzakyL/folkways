package io.github.izakyl.folkways.vanilla.orders;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Registering;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;

public final class OrdersPlugin {

    private OrdersPlugin() {
    }

    public static void install(IEventBus modBus) {
        modBus.addListener(Declaring.class, OrdersContent::declare);
        modBus.addListener(Registering.class, OrdersContent::enroll);
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(OrdersScene::declare);
        }
    }
}
