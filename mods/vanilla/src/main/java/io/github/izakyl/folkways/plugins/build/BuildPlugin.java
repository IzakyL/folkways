package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.library.Shelves;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.ui.RegisteringUi;
import io.github.izakyl.folkways.plugins.build.draft.PatternPackets;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.Templates;
import io.github.izakyl.folkways.plugins.build.draft.client.DraftOffer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

public final class BuildPlugin {

    private BuildPlugin() {
    }

    public static void install(IEventBus modBus) {
        Shelves.register(Patterns.SHELF, Templates.SHELF, Templates.BLUEPRINTS);
        modBus.addListener(Declaring.class, BuildContent::declare);
        modBus.addListener(Registering.class, BuildContent::enroll);
        modBus.addListener(RegisteringUi.class, event -> event.page(BuildContent.BLUEPRINTS.id(), BlueprintsPage::new));
        modBus.addListener(BuildPackets::registerPayloads);
        modBus.addListener(PatternPackets::registerPayloads);
        NeoForge.EVENT_BUS.addListener(AddReloadListenerEvent.class, event ->
            event.addListener((ResourceManagerReloadListener) Patterns::reload));
        NeoForge.EVENT_BUS.addListener(AddReloadListenerEvent.class, event ->
            event.addListener((ResourceManagerReloadListener) resources -> LocalMinerals.clearProfiles()));
        NeoForge.EVENT_BUS.addListener(PatternPackets::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(BuildPackets::onPlayerLoggedOut);
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(DraftOffer::register);
            modBus.addListener(BlueprintCaptureOffer::register);
            modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(HeldBlueprints::install));
            modBus.addListener(BuildScene::declare);
        }
    }
}
