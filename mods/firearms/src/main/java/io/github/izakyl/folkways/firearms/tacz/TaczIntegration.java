package io.github.izakyl.folkways.firearms.tacz;

import io.github.izakyl.folkways.core.base.persist.upgrade.ConfigRenames;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;

public final class TaczIntegration {
    private TaczIntegration() {
    }

    public static void install(IEventBus modBus, ModContainer container) {
        ConfigRenames.register("folkways-tacz-server.toml", "folkways_firearms-server.toml");
        container.registerConfig(ModConfig.Type.SERVER, TaczConfig.SPEC);
        TaczPlugin.install(modBus);
    }
}
