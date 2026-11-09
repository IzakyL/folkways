package io.github.izakyl.folkways.golems;

import io.github.izakyl.folkways.golems.upgrade.GolemsUpgrades;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(FolkwaysGolemsMod.ID)
public final class FolkwaysGolemsMod {

    public static final String ID = "folkways_golems";

    public FolkwaysGolemsMod(IEventBus modBus) {
        GolemsUpgrades.register();
        GolemPlugin.install(modBus);
    }
}
