package io.github.izakyl.folkways.cookery;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Registering;
import net.neoforged.bus.api.IEventBus;

public final class CookeryPlugin {
    private CookeryPlugin() {
    }

    public static void install(IEventBus modBus) {
        if (net.neoforged.fml.ModList.get().isLoaded("kaleidoscope_tavern")) io.github.izakyl.folkways.cookery.tavern.TavernCounters.register(modBus);
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) modBus.addListener(CookeryScenes::declare);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            io.github.izakyl.folkways.cookery.millstone.MillstoneDrive::joined);
        modBus.addListener(Declaring.class, CookeryContent::declare);
        modBus.addListener(io.github.izakyl.folkways.core.api.Relating.class, event ->
            event.defaultParticipation(io.github.izakyl.folkways.plugins.person.PersonBody.ID,
                io.github.izakyl.folkways.core.api.participation.Stake.urge(CookeryContent.ID),
                io.github.izakyl.folkways.core.api.participation.Participation.ALWAYS));
        modBus.addListener(Registering.class, CookeryContent::enroll);
    }
}
