package io.github.izakyl.folkways.core;

import io.github.izakyl.folkways.core.base.FolkwaysMixinPlugin;
import io.github.izakyl.folkways.core.base.Ledger;
import io.github.izakyl.folkways.core.base.persist.upgrade.Upgrades;
import io.github.izakyl.folkways.core.colony.api.Cadence;
import io.github.izakyl.folkways.core.colony.internal.ColonyLookup;
import io.github.izakyl.folkways.core.colony.internal.ColonyRegistry;
import io.github.izakyl.folkways.core.colony.internal.ColonyRuntime;
import io.github.izakyl.folkways.core.colony.internal.Contributions;
import io.github.izakyl.folkways.core.colony.internal.WorkDecoratorRegistry;
import io.github.izakyl.folkways.core.effect.perk.GlobalPerks;
import io.github.izakyl.folkways.core.effect.perk.PerkPool;
import io.github.izakyl.folkways.core.equipment.internal.EquipmentIntegration;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.extension.api.Relating;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import io.github.izakyl.folkways.core.front.engine.Front;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.net.ColonyOverviews;
import io.github.izakyl.folkways.core.front.ui.UiRegistrations;
import io.github.izakyl.folkways.core.inventory.internal.PortHooks;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemQuery;
import io.github.izakyl.folkways.core.library.LibraryCommand;
import io.github.izakyl.folkways.core.library.LooseFolder;
import io.github.izakyl.folkways.core.library.Shelves;
import io.github.izakyl.folkways.core.plan.internal.Refinements;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.ResidentKinds;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.core.resident.internal.EquipmentCoordinator;
import io.github.izakyl.folkways.core.resident.internal.PackKeepRegistry;
import io.github.izakyl.folkways.core.resident.internal.TaskToolLoans;
import io.github.izakyl.folkways.core.rts.RtsIntegration;
import io.github.izakyl.folkways.core.runtime.Labor;
import io.github.izakyl.folkways.core.shell.MobFitting;
import io.github.izakyl.folkways.core.shell.ae2.Ae2Integration;
import io.github.izakyl.folkways.core.shell.colossalchests.ChestsIntegration;
import io.github.izakyl.folkways.core.shell.mekanism.QioIntegration;
import io.github.izakyl.folkways.core.shell.neoforge.FolkwaysAttachments;
import io.github.izakyl.folkways.core.shell.rs2.Rs2Integration;
import io.github.izakyl.folkways.core.shell.crafting.NetworkPanel;
import io.github.izakyl.folkways.core.shell.storage.drawers.DrawersIntegration;
import io.github.izakyl.folkways.core.shell.storage.functional.FunctionalIntegration;
import io.github.izakyl.folkways.core.shell.storage.sophisticated.BackpacksIntegration;
import io.github.izakyl.folkways.core.shell.structure.CreateObstacles;
import io.github.izakyl.folkways.core.shell.structure.SablePassage;
import io.github.izakyl.folkways.core.shell.structure.SableSpaces;
import io.github.izakyl.folkways.core.shell.toms.TomsIntegration;
import io.github.izakyl.folkways.core.travel.api.Passages;
import io.github.izakyl.folkways.core.travel.internal.PathfindingLoad;
import io.github.izakyl.folkways.core.travel.internal.TickBudget;
import io.github.izakyl.folkways.core.upgrade.CoreUpgrades;
import io.github.izakyl.folkways.core.vocation.api.VocationGroup;
import io.github.izakyl.folkways.core.vocation.api.VocationSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.vocation.internal.VocationRegistry;
import io.github.izakyl.folkways.core.work.internal.HaulWork;
import io.github.izakyl.folkways.core.work.internal.KitRuleRegistry;
import io.github.izakyl.folkways.core.world.api.Structures;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.core.world.internal.LocalGrounds;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(FolkwaysCoreMod.CORE_ID)
public final class FolkwaysCoreMod {
    // Core owns the engine content namespace. Extensions use their own mod ids.
    public static final String MOD_ID = "folkways_core";

    // The mod this class is: the engine, which the other Folkways mods install their plugins over.
    public static final String CORE_ID = "folkways_core";

    public static final String NETWORK_VERSION = ModList.get().getModContainerById(CORE_ID)
        .orElseThrow().getModInfo().getVersion().toString() + "/3";

    private final Labor labor = new Labor();

    public FolkwaysCoreMod(IEventBus modBus, ModContainer container) {
        CoreUpgrades.register();
        PortHooks.install();
        container.registerConfig(ModConfig.Type.SERVER, FolkwaysConfig.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, FolkwaysClientConfig.SPEC);
        VocationRegistry.install();
        KitRuleRegistry.install();
        WorkDecoratorRegistry.install();
        EquipmentCoordinator.install();
        PackKeepRegistry.install();
        TaskToolLoans.install();
        fitTheShell(modBus);
        FolkwaysAttachments.register(modBus);
        Front.install(modBus);
        RtsIntegration.install(modBus);
        EquipmentIntegration.install(modBus);
        modBus.addListener(Declaring.class, event ->
            event.vocation(HaulWork.SPEC.ordered(0).grouped(VocationGroup.TRANSPORT)));
        modBus.addListener(Declaring.class, event -> event.vocation(
            VocationSpec.of(
                Vocations.NETWORK_SERVICE)
                .grouped(VocationGroup.TRANSPORT).ordered(10).allowedByDefault(false)));
        modBus.addListener(FMLCommonSetupEvent.class, FolkwaysCoreMod::openDeclarations);
        modBus.addListener(FolkwaysCoreMod::reportDegradedMixins);
        modBus.addListener(LooseFolder::addPackFinder);
        modBus.addListener(ModConfigEvent.Loading.class, event -> PerkPool.regrant());
        modBus.addListener(ModConfigEvent.Reloading.class, event -> PerkPool.regrant());
        NeoForge.EVENT_BUS.addListener(LibraryCommand::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(PlayerLoggedInEvent.class,
            event -> {
                if (event.getEntity() instanceof ServerPlayer player) {
                    ColonyRegistry.get(player.server).rejectPlayer(player);
                }
            });
        NeoForge.EVENT_BUS.addListener(labor::onServerTick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.TagsUpdatedEvent.class, event -> {
            Goods.forgetTags();
            ItemQuery.forget();
        });
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
    }

    private static void fitTheShell(IEventBus modBus) {
        if (ModList.get().isLoaded("accessories")) {
            io.github.izakyl.folkways.core.shell.accessories.AccessoriesIntegration.install();
        }
        if (ModList.get().isLoaded("curios") && !ModList.get().isLoaded("cclayer")
            && !ModList.get().isLoaded("accessories_compat_layer")) {
            io.github.izakyl.folkways.core.shell.curios.CuriosIntegration.install();
        }
        if (ModList.get().isLoaded("fxntstorage")) {
            io.github.izakyl.folkways.core.shell.fxntstorage.StorageIntegration.install();
        }
        if (ModList.get().isLoaded("toms_storage")) {
            TomsIntegration.install();
        }
        if (ModList.get().isLoaded("colossalchests")) {
            ChestsIntegration.install();
        }
        if (ModList.get().isLoaded("mekanism")) {
            QioIntegration.install();
        }
        if (ModList.get().isLoaded("refinedstorage")) {
            Rs2Integration.install(modBus);
        }
        if (ModList.get().isLoaded("ae2")) {
            Ae2Integration.install(modBus);
        }
        if (ModList.get().isLoaded("refinedstorage") || ModList.get().isLoaded("ae2")) {
            NetworkPanel.install(modBus);
        }
        if (ModList.get().isLoaded("sophisticatedstorage")) {
            io.github.izakyl.folkways.core.shell.storage.sophisticated.StorageIntegration.install();
        }
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            BackpacksIntegration.install();
        }
        if (ModList.get().isLoaded("storagedrawers")) {
            DrawersIntegration.install();
        }
        if (ModList.get().isLoaded("functionalstorage")) {
            FunctionalIntegration.install();
        }
        if (ModList.get().isLoaded("sable")) {
            WorldSpaces.install(new SableSpaces());
            modBus.addListener(Declaring.class, event -> event.passage(new SablePassage()));
        }
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            io.github.izakyl.folkways.core.shell.WornGear.install();
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
        event.enqueueWork(() -> {
            Upgrades.freeze();
            declareEverything();
        });
    }

    private static void declareEverything() {
        GlobalPerks.install(FolkwaysConfig::asceticAppetite);
        Ledger.open();
        try {
            ModLoader.postEventWrapContainerInModOrder(new Declaring(Refinements::register,
                Contributions::urges, Contributions::colony));
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
        TaskToolLoans.INSTANCE.clear();
        ColonyRuntime.closeAll(server);
        PathfindingLoad.forgetServer();
        TickBudget.forgetServer();
        ColonyOverviews.forgetServer();
        Cadence.forgetServer();
        ColonyFront.forgetAll();
        Shelves.forgetServer();
    }

    private static void reportDegradedMixins(FMLCommonSetupEvent event) {
        FolkwaysMixinPlugin.logDegradations();
    }

}
