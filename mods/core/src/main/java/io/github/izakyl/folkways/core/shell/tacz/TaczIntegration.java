package io.github.izakyl.folkways.core.shell.tacz;

import net.neoforged.bus.api.IEventBus;

public final class TaczIntegration {
    private TaczIntegration() {
    }

    public static void install(IEventBus modBus) {
        TaczPlugin.install(modBus);
    }
}
