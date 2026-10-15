package io.github.izakyl.folkways.plugins.cookery;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Registering;
import net.neoforged.bus.api.IEventBus;

public final class CookeryPlugin {
    private CookeryPlugin() {
    }

    public static void install(IEventBus modBus) {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            io.github.izakyl.folkways.plugins.cookery.millstone.MillstoneDrive::joined);
        modBus.addListener(Declaring.class, CookeryContent::declare);
        modBus.addListener(io.github.izakyl.folkways.core.api.Relating.class, event ->
            event.defaultParticipation(io.github.izakyl.folkways.plugins.person.PersonBody.ID,
                io.github.izakyl.folkways.core.api.participation.Stake.urge(CookeryContent.ID),
                io.github.izakyl.folkways.core.api.participation.Participation.ALWAYS));
        modBus.addListener(Registering.class, CookeryContent::enroll);
    }
}
