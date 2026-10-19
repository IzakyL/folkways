package io.github.izakyl.folkways;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.base.FolkwaysMixinPlugin;
import io.github.izakyl.folkways.base.Ledger;
import io.github.izakyl.folkways.core.api.Relating;
import io.github.izakyl.folkways.core.api.colony.Cadence;
import io.github.izakyl.folkways.core.api.colony.Contributions;
import io.github.izakyl.folkways.library.LibraryCommand;
import io.github.izakyl.folkways.library.LooseFolder;
import io.github.izakyl.folkways.library.Shelves;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.passage.Passages;
import io.github.izakyl.folkways.effect.perk.GlobalPerks;
import io.github.izakyl.folkways.effect.perk.PerkPool;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.resident.body.FolkwaysAttachments;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemQuery;
import io.github.izakyl.folkways.core.api.terms.Structures;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.engine.colony.ColonyLookup;
import io.github.izakyl.folkways.core.engine.colony.ColonyRuntime;
import io.github.izakyl.folkways.core.engine.labor.Labor;
import io.github.izakyl.folkways.core.engine.plan.haul.Haul;
import io.github.izakyl.folkways.core.engine.travel.PathfindingLoad;
import io.github.izakyl.folkways.core.engine.travel.TravelBudget;
import io.github.izakyl.folkways.core.engine.travel.local.LocalGrounds;
import io.github.izakyl.folkways.core.shell.MobFitting;
import io.github.izakyl.folkways.core.shell.structure.CreateObstacles;
import io.github.izakyl.folkways.core.shell.structure.SablePassage;
import io.github.izakyl.folkways.core.shell.structure.SableSpaces;
import io.github.izakyl.folkways.front.engine.Enrollments;
import io.github.izakyl.folkways.front.engine.Front;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.net.ColonyOverviews;
import io.github.izakyl.folkways.front.ui.UiRegistrations;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(FolkwaysMod.CORE_ID)
public final class FolkwaysMod {
    // The namespace every Folkways mod registers under and ships its resources in; no one Folkways mod owns it as an id.
    public static final String MOD_ID = "folkways";

    // The mod this class is: the engine, which the other Folkways mods install their plugins over.
    public static final String CORE_ID = "folkways_core";

    public static final String NETWORK_VERSION = "1";

    private final Labor labor = new Labor();

    public FolkwaysMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, FolkwaysConfig.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, FolkwaysClientConfig.SPEC);
        fitTheShell(modBus);
        FolkwaysAttachments.register(modBus);
        Front.install(modBus);
        modBus.addListener(Declaring.class, event -> event.vocation(Haul.SPEC));
        modBus.addListener(FMLCommonSetupEvent.class, FolkwaysMod::openDeclarations);
        modBus.addListener(FolkwaysMod::reportDegradedMixins);
        modBus.addListener(LooseFolder::addPackFinder);
        modBus.addListener(ModConfigEvent.Loading.class, event -> PerkPool.regrant());
        modBus.addListener(ModConfigEvent.Reloading.class, event -> PerkPool.regrant());
        NeoForge.EVENT_BUS.addListener(LibraryCommand::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(labor::onServerTick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.TagsUpdatedEvent.class, event -> {
            Goods.forgetTags();
            ItemQuery.forget();
        });
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
    }

    private static void fitTheShell(IEventBus modBus) {
        if (ModList.get().isLoaded("sable")) {
            WorldSpaces.install(new SableSpaces());
            modBus.addListener(Declaring.class, event -> event.passage(new SablePassage()));
        }
        ColonyLookup.install();
        PathfindingLoad.install();
        LocalGrounds.install();
        MobFitting.install();
        if (ModList.get().isLoaded(CreateObstacles.CREATE_MOD_ID)) {
            Structures.install(new CreateObstacles());
        }
    }

    private static void openDeclarations(FMLCommonSetupEvent event) {
        event.enqueueWork(FolkwaysMod::declareEverything);
    }

    private static void declareEverything() {
        GlobalPerks.install(FolkwaysConfig::asceticAppetite);
        Ledger.open();
        try {
            ModLoader.postEventWrapContainerInModOrder(new Declaring());
        } finally {
            Ledger.close();
        }
        Relating relating = new Relating();
        try {
            ModLoader.postEventWrapContainerInModOrder(relating);
        } finally {
            relating.close();
        }
        PerkPool.verify(Vocations.ids());
        Participations.verify(ResidentKinds.ids(), declared());
        ResidentKinds.verify();
        Enrollments.gather();
        UiRegistrations.gather();
    }

    private static Set<Stake> declared() {
        return Stream.of(
                Contributions.ids().stream().map(Stake::urge),
                Vocations.ids().stream().map(Stake::vocation),
                Passages.ids().stream().map(Stake::passage))
            .flatMap(stakes -> stakes)
            .collect(Collectors.toSet());
    }

    private void onServerStarting(ServerStartingEvent event) {
        ColonyRuntime.opened(event.getServer());
        labor.begin(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        forgetServer(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        forgetServer(event.getServer());
    }

    private void forgetServer(MinecraftServer server) {
        labor.shutdown();
        ColonyRuntime.closeAll(server);
        PathfindingLoad.forgetServer();
        TravelBudget.forgetServer();
        ColonyOverviews.forgetServer();
        Cadence.forgetServer();
        ColonyFront.forgetAll();
        Shelves.forgetServer();
    }

    private static void reportDegradedMixins(FMLCommonSetupEvent event) {
        FolkwaysMixinPlugin.logDegradations();
    }

}
