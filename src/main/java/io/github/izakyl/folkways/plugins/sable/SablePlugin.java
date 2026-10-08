package io.github.izakyl.folkways.plugins.sable;

import io.github.izakyl.folkways.core.api.Declaring;
import net.neoforged.bus.api.IEventBus;

public final class SablePlugin {

    private SablePlugin() {
    }

    public static void install(IEventBus modBus) {
        modBus.addListener(Declaring.class, event -> event.passage(new SablePassage()));
    }
}
