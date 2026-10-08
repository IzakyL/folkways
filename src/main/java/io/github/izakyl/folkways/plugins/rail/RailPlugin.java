package io.github.izakyl.folkways.plugins.rail;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.terms.Keepouts;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.plugins.CreateMod;
import io.github.izakyl.folkways.plugins.rail.domain.Departures;
import io.github.izakyl.folkways.plugins.rail.domain.RailContent;
import io.github.izakyl.folkways.plugins.rail.domain.RailHighlight;
import io.github.izakyl.folkways.plugins.rail.domain.RailPackets;
import io.github.izakyl.folkways.plugins.rail.domain.Trackways;
import io.github.izakyl.folkways.plugins.rail.passage.RailPassage;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

public final class RailPlugin {

    private RailPlugin() {
    }

    public static void install(IEventBus modBus) {
        modBus.addListener(Declaring.class, event -> {
            RailContent.declare(event);
            event.passage(new RailPassage());
        });
        modBus.addListener(Registering.class, RailContent::enroll);
        modBus.addListener(RailPackets::registerPayloads);
        if (CreateMod.loaded()) {
            modBus.addListener(FMLCommonSetupEvent.class, event -> event.enqueueWork(() -> Keepouts.install(new Trackways())));
            modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(RailHighlight::install));
            NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> Departures.forgetServer());
            if (FMLEnvironment.dist.isClient()) {
                modBus.addListener(RailScene::declare);
            }
        }
    }
}
