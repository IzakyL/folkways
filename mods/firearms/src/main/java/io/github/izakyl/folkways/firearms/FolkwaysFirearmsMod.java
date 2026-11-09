package io.github.izakyl.folkways.firearms;

import io.github.izakyl.folkways.firearms.upgrade.FirearmsUpgrades;
import io.github.izakyl.folkways.firearms.tacz.TaczIntegration;
import io.github.izakyl.folkways.firearms.tacz.upgrade.TaczUpgrades;
import io.github.izakyl.folkways.firearms.warfare.WarfareIntegration;
import io.github.izakyl.folkways.firearms.warfare.upgrade.WarfareUpgrades;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

@Mod(FolkwaysFirearmsMod.ID)
public final class FolkwaysFirearmsMod {

    public static final String ID = "folkways_firearms";

    public FolkwaysFirearmsMod(IEventBus modBus, ModContainer container) {
        FirearmsUpgrades.register();
        if (ModList.get().isLoaded("tacz")) {
            TaczUpgrades.register();
            TaczIntegration.install(modBus, container);
        }
        if (ModList.get().isLoaded("superbwarfare")) {
            WarfareUpgrades.register();
            WarfareIntegration.install(modBus);
        }
    }
}
