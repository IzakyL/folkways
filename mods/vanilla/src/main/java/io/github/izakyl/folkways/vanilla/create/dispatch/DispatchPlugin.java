package io.github.izakyl.folkways.vanilla.create.dispatch;

import io.github.izakyl.folkways.core.effect.perk.PerkPool;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.shell.create.dispatch.MailGate;
import io.github.izakyl.folkways.vanilla.create.VanillaCreateConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

public final class DispatchPlugin {

    private DispatchPlugin() {
    }

    public static void install(IEventBus modBus) {
        MailGate.install((colony, order, train, station) -> colony
            .service(DispatchContent.ID, DispatchPresence.class)
            .flatMap(presence -> presence.boards(order, train, station)));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> DispatchRoutes.forgetServer());
        modBus.addListener(ModConfigEvent.Loading.class, DispatchPlugin::regrant);
        modBus.addListener(ModConfigEvent.Reloading.class, DispatchPlugin::regrant);
        modBus.addListener(Declaring.class, DispatchContent::declare);
        modBus.addListener(Registering.class, DispatchContent::enroll);
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(DispatchScene::declare);
        }
    }

    private static void regrant(ModConfigEvent event) {
        if (event.getConfig().getSpec() == VanillaCreateConfig.SPEC) {
            PerkPool.regrant();
        }
    }
}
