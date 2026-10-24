package io.github.izakyl.folkways.warfare;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(FolkwaysWarfareMod.ID)
public final class FolkwaysWarfareMod {
    public static final String ID = "folkways_warfare";

    public FolkwaysWarfareMod(IEventBus bus) {
        bus.addListener(Declaring.class, WarfareWeapons::declare);
        bus.addListener(RegisterCapabilitiesEvent.class, event ->
            event.registerEntity(Capabilities.ItemHandler.ENTITY,
                PersonBody.RESIDENT.get(),
                (body, context) -> new InvWrapper(body.pack())));
        bus.addListener(Declaring.class, WarfareStations::declare);
        NeoForge.EVENT_BUS.addListener(WarfareWeapon::shot);
        NeoForge.EVENT_BUS.addListener(WarfareWeapons::ticked);
        NeoForge.EVENT_BUS.addListener(WarfareStations::left);
    }
}
