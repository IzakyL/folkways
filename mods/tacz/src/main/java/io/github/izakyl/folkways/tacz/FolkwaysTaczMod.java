package io.github.izakyl.folkways.tacz;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(FolkwaysTaczMod.ID)
public final class FolkwaysTaczMod {
    public static final String ID = "folkways_tacz";

    public FolkwaysTaczMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, TaczConfig.SPEC, "folkways-tacz-server.toml");
        TaczPlugin.install(modBus);
    }
}
