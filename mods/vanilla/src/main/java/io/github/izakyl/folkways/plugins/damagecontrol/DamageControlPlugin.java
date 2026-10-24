package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Registering;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;

public final class DamageControlPlugin {
    private DamageControlPlugin() {
    }

    public static void install(IEventBus bus) {
        bus.addListener(Declaring.class, DamageControlContent::declare);
        bus.addListener(Registering.class, DamageControlContent::enroll);
        if (FMLEnvironment.dist.isClient()) {
            bus.addListener(DamageControlScene::declare);
        }
    }
}
