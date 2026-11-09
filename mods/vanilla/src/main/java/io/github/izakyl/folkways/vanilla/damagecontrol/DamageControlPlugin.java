package io.github.izakyl.folkways.vanilla.damagecontrol;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Registering;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;

public final class DamageControlPlugin {
    private DamageControlPlugin() {
    }

    public static void install(IEventBus bus) {
        bus.addListener(DamageControlCommands::register);
        bus.addListener(Declaring.class, DamageControlContent::declare);
        bus.addListener(Registering.class, DamageControlContent::enroll);
        if (FMLEnvironment.dist.isClient()) {
            bus.addListener(DamageControlScene::declare);
            bus.addListener(DamageControlTool::install);
        }
    }
}
