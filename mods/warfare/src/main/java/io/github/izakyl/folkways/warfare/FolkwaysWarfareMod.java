package io.github.izakyl.folkways.warfare;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.warfare.upgrade.WarfareUpgrades;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

@Mod(FolkwaysWarfareMod.ID)
public final class FolkwaysWarfareMod {

    public static final String ID = "folkways_warfare";

    public FolkwaysWarfareMod(IEventBus bus) {
        WarfareUpgrades.register();
        if (FMLEnvironment.dist.isClient()) {
            WarfareClient.install();
        }
        bus.addListener(Declaring.class, WarfareWeapons::declare);
        bus.addListener(Declaring.class, WarfareStations::declare);
        bus.addListener(Registering.class, WarfareStations::enroll);
        NeoForge.EVENT_BUS.addListener(WarfareWeapons::ticked);
        NeoForge.EVENT_BUS.addListener(WarfareStations::left);
        NeoForge.EVENT_BUS.addListener(WarfareExplosions::joined);
    }
}
