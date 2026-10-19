package io.github.izakyl.folkways.create;

import io.github.izakyl.folkways.create.build.CreateBuildPlugin;
import io.github.izakyl.folkways.create.dispatch.DispatchPlugin;
import io.github.izakyl.folkways.create.rail.RailPlugin;
import net.neoforged.bus.api.IEventBus;

// Everything Create brings to a colony, installed only when Create is there.
public final class CreatePlugins {

    private CreatePlugins() {
    }

    public static void install(IEventBus modBus) {
        CreateBuildPlugin.install(modBus);
        RailPlugin.install(modBus);
        DispatchPlugin.install(modBus);
    }
}
