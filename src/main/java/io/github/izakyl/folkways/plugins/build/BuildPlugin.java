package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.plugins.CreateMod;
import io.github.izakyl.folkways.plugins.build.draft.PatternPackets;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.client.DraftOffer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

public final class BuildPlugin {

    private BuildPlugin() {
    }

    public static void install(IEventBus modBus) {
        modBus.addListener(Declaring.class, BuildContent::declare);
        modBus.addListener(Registering.class, BuildContent::enroll);
        modBus.addListener(BuildPackets::registerPayloads);
        modBus.addListener(PatternPackets::registerPayloads);
        NeoForge.EVENT_BUS.addListener(AddReloadListenerEvent.class, event ->
            event.addListener((ResourceManagerReloadListener) Patterns::reload));
        NeoForge.EVENT_BUS.addListener(PatternPackets::onPlayerLoggedIn);
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(DraftOffer::register);
            modBus.addListener(BuildScene::declare);
        }
        if (CreateMod.loaded()) {
            CreateTrack.install();
        }
    }
}
