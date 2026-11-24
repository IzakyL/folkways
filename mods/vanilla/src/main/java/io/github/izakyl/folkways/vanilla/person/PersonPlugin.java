package io.github.izakyl.folkways.vanilla.person;

import io.github.izakyl.folkways.core.colony.api.PackKeeps;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.extension.api.Relating;
import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.ui.Bay;
import io.github.izakyl.folkways.core.front.api.ui.Editor;
import io.github.izakyl.folkways.core.front.api.ui.RegisteringUi;
import io.github.izakyl.folkways.core.library.Shelves;
import io.github.izakyl.folkways.vanilla.person.fear.FearContent;
import io.github.izakyl.folkways.vanilla.person.living.Housing;
import io.github.izakyl.folkways.vanilla.person.living.LivingContent;
import io.github.izakyl.folkways.vanilla.person.living.LivingInlay;
import io.github.izakyl.folkways.vanilla.person.look.LookPool;
import io.github.izakyl.folkways.vanilla.person.look.LookSet;
import io.github.izakyl.folkways.vanilla.person.look.LooksPage;
import io.github.izakyl.folkways.vanilla.person.look.ModelLibrary;
import io.github.izakyl.folkways.vanilla.person.look.ResidentLooks;
import io.github.izakyl.folkways.vanilla.person.look.ResidentModelPackets;
import io.github.izakyl.folkways.vanilla.person.look.ResidentModels;
import io.github.izakyl.folkways.vanilla.person.name.NamesPage;
import io.github.izakyl.folkways.vanilla.person.name.ResidentNames;
import io.github.izakyl.folkways.vanilla.person.ride.Riding;
import io.github.izakyl.folkways.vanilla.person.walk.BreathingPresence;
import io.github.izakyl.folkways.vanilla.person.walk.Doorways;
import io.github.izakyl.folkways.vanilla.person.walk.Walking;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

public final class PersonPlugin {

    private static final ResourceLocation WORK_DEFAULTS =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "work_defaults");
    private static final ResourceLocation NAMES = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "names");
    private static final ResourceLocation LOOKS = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "looks");

    private PersonPlugin() {
    }

    public static void install(IEventBus modBus) {
        PersonBody.register(modBus);
        modBus.addListener(PersonBody::createAttributes);
        modBus.addListener(Declaring.class, event -> {
            event.locomotion(Walking.INSTANCE);
            event.locomotion(Riding.INSTANCE);
            PersonContent.declare(event);
            PersonBody.declare(event);
            LivingContent.declare(event);
            FearContent.declare(event);
            BreathingPresence.declare(event);
        });
        modBus.addListener(Relating.class, PersonBody::relate);
        modBus.addListener(Registering.class, event -> {
            PersonContent.enroll(event);
            LivingContent.enroll(event);
        });
        modBus.addListener(RegisteringUi.class, event -> {
            event.inlay(Bay.RESIDENT, LookPool.OWNER, PersonInlay::new);
            event.inlay(Bay.RESIDENT, LivingContent.ID, LivingInlay::new);
            event.outline(Housing::pillow);
            event.editor(ColonyPanel.Page.RESIDENTS, new Editor(WORK_DEFAULTS, "folkways.tab.work_defaults",
                ResourceLocation.withDefaultNamespace("iron_pickaxe"), 420, 260, WorkDefaultsPage::new));
            event.editor(ColonyPanel.Page.RESIDENTS, new Editor(NAMES, "folkways.tab.names",
                ResourceLocation.withDefaultNamespace("name_tag"), NamesPage::new));
            event.editor(ColonyPanel.Page.RESIDENTS, new Editor(LOOKS, "folkways.tab.looks",
                ResourceLocation.withDefaultNamespace("player_head"), LooksPage::new));
        });
        Shelves.register(ModelLibrary.SHELF, ModelLibrary.SKINS);
        modBus.addListener(ResidentLooks::registerDataPackRegistry);
        modBus.addListener(LookSet::registerDataPackRegistry);
        modBus.addListener(ResidentNames::registerDataPackRegistry);
        modBus.addListener(ResidentModelPackets::registerPayloads);
        NeoForge.EVENT_BUS.addListener(ResidentModels::onAddReloadListener);
        NeoForge.EVENT_BUS.addListener(LivingContent::exerted);
        NeoForge.EVENT_BUS.addListener(LivingContent::petRemoved);
        PackKeeps.add(LivingContent::rations);
        NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, LivingContent::blockChanged);
        NeoForge.EVENT_BUS.addListener(BlockEvent.EntityPlaceEvent.class, LivingContent::blockChanged);
        NeoForge.EVENT_BUS.addListener(ResidentModelPackets::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> ResidentModels.forgetServer());
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> ResidentModels.forgetServer());
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> Doorways.forgetServer());
        NeoForge.EVENT_BUS.addListener(Doorways::onLeaveLevel);
        if (FMLEnvironment.dist.isClient()) {
            PersonClient.install(modBus);
        }
    }
}
