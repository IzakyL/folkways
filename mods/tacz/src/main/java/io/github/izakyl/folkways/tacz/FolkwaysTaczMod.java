package io.github.izakyl.folkways.tacz;

import io.github.izakyl.folkways.tacz.upgrade.TaczUpgrades;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(FolkwaysTaczMod.ID)
public final class FolkwaysTaczMod {

    public static final String ID = "folkways_tacz";

    public FolkwaysTaczMod(IEventBus modBus, ModContainer container) {
        TaczUpgrades.register();
        container.registerConfig(ModConfig.Type.SERVER, TaczConfig.SPEC, "folkways-tacz-server.toml");
        TaczPlugin.install(modBus);
    }
}
