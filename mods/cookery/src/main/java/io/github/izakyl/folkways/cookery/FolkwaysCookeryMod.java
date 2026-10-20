package io.github.izakyl.folkways.cookery;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(FolkwaysCookeryMod.ID)
public final class FolkwaysCookeryMod {
    public static final String ID = "folkways_cookery";

    public FolkwaysCookeryMod(IEventBus modBus) {
        CookeryPlugin.install(modBus);
    }
}
