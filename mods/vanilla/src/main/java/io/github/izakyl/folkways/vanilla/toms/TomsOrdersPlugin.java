package io.github.izakyl.folkways.vanilla.toms;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

// Ordering from the colony at a Tom's Simple Storage terminal. The payloads register whatever is installed, so a
// client and server always agree on them; the button and requests only exist with Tom's Simple Storage.
public final class TomsOrdersPlugin {

    private TomsOrdersPlugin() {
    }

    public static void install(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, TomsOrderNetwork::register);
        if (!ModList.get().isLoaded("toms_storage")) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> TomsOrders.forget());
        if (FMLEnvironment.dist.isClient()) {
            TomsOrdersClient.install();
        }
    }
}
