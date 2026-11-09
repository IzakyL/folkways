package io.github.izakyl.folkways.vanilla.person;

import io.github.izakyl.folkways.vanilla.person.living.HouseholdScene;
import io.github.izakyl.folkways.vanilla.person.look.ClientMaidModels;
import io.github.izakyl.folkways.vanilla.person.look.ClientResidentModels;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;

final class PersonClient {

    private PersonClient() {
    }

    static void install(IEventBus modBus) {
        modBus.addListener(PersonClient::registerRenderers);
        modBus.addListener(PersonClient::addModelPack);
        modBus.addListener(ResidentsScene::declare);
        modBus.addListener(HouseholdScene::declare);
        modBus.addListener(io.github.izakyl.folkways.vanilla.person.look.PersonPortrait::install);
        NeoForge.EVENT_BUS.addListener(ClientResidentModels::onLoggingOut);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PersonBody.RESIDENT.get(), ResidentRenderer::new);
    }

    private static void addModelPack(AddPackFindersEvent event) {
        ClientResidentModels.addPackFinder(event);
        ClientMaidModels.addPackFinder(event);
    }
}
