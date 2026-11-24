package io.github.izakyl.folkways.core.shell.golems.resident;

import net.neoforged.bus.api.IEventBus;

public final class Golems {

    private Golems() {
    }

    public static void install(IEventBus gameBus) {
        gameBus.register(GolemEvents.class);
    }
}
