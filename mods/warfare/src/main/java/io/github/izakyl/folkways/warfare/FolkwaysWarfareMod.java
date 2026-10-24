package io.github.izakyl.folkways.warfare;

import io.github.izakyl.folkways.core.api.Declaring;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(FolkwaysWarfareMod.ID)
public final class FolkwaysWarfareMod {
    public static final String ID = "folkways_warfare";

    public FolkwaysWarfareMod(IEventBus bus) {
        bus.addListener(Declaring.class, WarfareWeapons::declare);
        bus.addListener(Declaring.class, WarfareStations::declare);
        NeoForge.EVENT_BUS.addListener(WarfareWeapon::shot);
        NeoForge.EVENT_BUS.addListener(WarfareWeapons::ticked);
        NeoForge.EVENT_BUS.addListener(WarfareStations::left);
        NeoForge.EVENT_BUS.addListener(WarfareExplosions::joined);
    }
}
