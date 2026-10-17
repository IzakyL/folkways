package io.github.izakyl.folkways.create.build;

import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.plugins.build.BuildContent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;

// What Create adds to building: track laid and joined as Create lays it, and a schematic handed to the colony.
public final class CreateBuildPlugin {

    private CreateBuildPlugin() {
    }

    public static void install(IEventBus modBus) {
        CreateTrack.install();
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(() ->
                Scenes.add(BuildContent.ID, Scenes.scene("create_handoff", CreateHandoffScene::program))));
        }
    }
}
