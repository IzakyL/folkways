package io.github.izakyl.folkways.modulargolems;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(FolkwaysModularGolemsMod.ID)
public final class FolkwaysModularGolemsMod {

    public static final String ID = "folkways_modulargolems";

    public FolkwaysModularGolemsMod(IEventBus modBus) {
        GolemPlugin.install(modBus);
    }
}
