package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.Relating;
import io.github.izakyl.folkways.core.api.resident.body.PackKeeps;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.ui.Bay;
import io.github.izakyl.folkways.front.api.ui.RegisteringUi;
import io.github.izakyl.folkways.plugins.person.fear.FearContent;
import io.github.izakyl.folkways.plugins.person.living.Housing;
import io.github.izakyl.folkways.plugins.person.living.LivingContent;
import io.github.izakyl.folkways.plugins.person.look.LookPool;
import io.github.izakyl.folkways.plugins.person.look.LookSet;
import io.github.izakyl.folkways.plugins.person.look.LooksPage;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import io.github.izakyl.folkways.plugins.person.look.ResidentModelPackets;
import io.github.izakyl.folkways.plugins.person.look.ResidentModels;
import io.github.izakyl.folkways.plugins.person.name.NamesPage;
import io.github.izakyl.folkways.plugins.person.name.ResidentNames;
import io.github.izakyl.folkways.plugins.person.walk.Walking;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

public final class PersonPlugin {

    private PersonPlugin() {
    }

    public static void install(IEventBus modBus) {
        PersonBody.register(modBus);
        modBus.addListener(PersonBody::createAttributes);
        modBus.addListener(Declaring.class, event -> {
            event.locomotion(Walking.INSTANCE);
            PersonContent.declare(event);
            PersonBody.declare(event);
            LivingContent.declare(event);
            FearContent.declare(event);
        });
        modBus.addListener(Relating.class, PersonBody::relate);
        modBus.addListener(Registering.class, event -> {
            PersonContent.enroll(event);
            LivingContent.enroll(event);
        });
        modBus.addListener(RegisteringUi.class, event -> {
            event.page(PersonContent.LOOKS.id(), LooksPage::new);
            event.page(PersonContent.NAMES.id(), NamesPage::new);
            event.inlay(Bay.RESIDENT, LookPool.OWNER, PersonInlay::new);
            event.outline(Housing::pillow);
        });
        modBus.addListener(ResidentLooks::registerDataPackRegistry);
        modBus.addListener(LookSet::registerDataPackRegistry);
        modBus.addListener(ResidentNames::registerDataPackRegistry);
        modBus.addListener(ResidentModelPackets::registerPayloads);
        NeoForge.EVENT_BUS.addListener(ResidentModels::onAddReloadListener);
        NeoForge.EVENT_BUS.addListener(LivingContent::exerted);
        PackKeeps.add(LivingContent::rations);
        NeoForge.EVENT_BUS.addListener(ResidentModelPackets::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> ResidentModels.forgetServer());
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> ResidentModels.forgetServer());
        if (FMLEnvironment.dist.isClient()) {
            PersonClient.install(modBus);
        }
    }
}
