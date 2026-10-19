package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Registering;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

public final class PatrolPlugin {

    private PatrolPlugin() {
    }

    public static void install(IEventBus modBus) {
        modBus.addListener(Declaring.class, PatrolContent::declare);
        modBus.addListener(Registering.class, PatrolContent::enroll);
        NeoForge.EVENT_BUS.register(Wards.class);
    }
}
