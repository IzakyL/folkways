package io.github.izakyl.folkways.vanilla.create.build;

import io.github.izakyl.folkways.core.front.api.ponder.Scenes;
import io.github.izakyl.folkways.core.shell.create.build.CreateHandoffTool;
import io.github.izakyl.folkways.vanilla.build.BlueprintHandoffPanel;
import io.github.izakyl.folkways.vanilla.build.BuildContent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;

// What Create adds to building: track laid and joined as Create lays it, and a schematic handed to the colony.
public final class CreateBuildPlugin {

    private CreateBuildPlugin() {
    }

    public static void install(IEventBus modBus) {
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(() -> {
                CreateHandoffTool.install(() -> BlueprintHandoffPanel.open(CreateBlueprintHandoff::handOff));
                Scenes.add(BuildContent.ID, Scenes.scene("folkways_vanilla", "create_handoff",
                    CreateHandoffScene::program));
            }));
        }
    }
}
