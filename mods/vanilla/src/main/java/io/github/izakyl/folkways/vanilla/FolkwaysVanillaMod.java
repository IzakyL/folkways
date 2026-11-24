package io.github.izakyl.folkways.vanilla;


import io.github.izakyl.folkways.core.effect.perk.PerkPool;
import io.github.izakyl.folkways.core.shell.api.OptionalIntegrations;
import io.github.izakyl.folkways.vanilla.carts.CartsPlugin;
import io.github.izakyl.folkways.vanilla.chiselsandbits.CandBPatterns;
import io.github.izakyl.folkways.vanilla.combat.FacilityEngagements;
import io.github.izakyl.folkways.vanilla.cookery.CookeryPlugin;
import io.github.izakyl.folkways.vanilla.create.VanillaCreateConfig;
import io.github.izakyl.folkways.vanilla.create.build.CreateBuildPlugin;
import io.github.izakyl.folkways.vanilla.create.dispatch.DispatchPlugin;
import io.github.izakyl.folkways.vanilla.create.elevator.ElevatorPlugin;
import io.github.izakyl.folkways.vanilla.create.firefighting.FirefightingIntegration;
import io.github.izakyl.folkways.vanilla.create.rail.RailPlugin;
import io.github.izakyl.folkways.vanilla.create.rail.domain.RailPackets;
import io.github.izakyl.folkways.vanilla.epicfight.ResidentEpicFightIntegration;
import io.github.izakyl.folkways.vanilla.golems.GolemWork;
import io.github.izakyl.folkways.vanilla.tacz.TaczResidentIntegration;
import io.github.izakyl.folkways.vanilla.upgrade.VanillaUpgrades;
import io.github.izakyl.folkways.vanilla.warfare.WarfareResidentIntegration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

// Folkways Vanilla: the work residents do in a world of vanilla blocks, installed over Folkways Core.
@Mod(FolkwaysVanillaMod.ID)
public final class FolkwaysVanillaMod {

    public static final String ID = "folkways_vanilla";

    public FolkwaysVanillaMod(IEventBus modBus, ModContainer container) {
        VanillaUpgrades.register();
        FacilityEngagements.install();
        if (OptionalIntegrations.MODULAR_GOLEMS.enabled()) {
            GolemWork.install(modBus);
        }
        if (OptionalIntegrations.CHISELS_AND_BITS.enabled()) {
            CandBPatterns.install(modBus, container);
        }
        if (ModList.get().isLoaded("create")) {
            modBus.addListener(RailPackets::registerPayloads);
        }
        if (OptionalIntegrations.CREATE.enabled()) {
            VanillaCreateConfig.register(container);
            CreateBuildPlugin.install(modBus);
            RailPlugin.install(modBus);
            ElevatorPlugin.install(modBus);
            DispatchPlugin.install(modBus);
        }
        if (OptionalIntegrations.FIREFIGHTING.enabled()) {
            FirefightingIntegration.install(modBus);
        }
        container.registerConfig(ModConfig.Type.SERVER, FolkwaysVanillaConfig.SPEC);
        // The quick hands read their ladders from this config, so its loading grants them again.
        modBus.addListener(ModConfigEvent.Loading.class, event -> {
            if (event.getConfig().getSpec() == FolkwaysVanillaConfig.SPEC) {
                PerkPool.regrant();
            }
        });
        modBus.addListener(ModConfigEvent.Reloading.class, event -> {
            if (event.getConfig().getSpec() == FolkwaysVanillaConfig.SPEC) {
                PerkPool.regrant();
            }
        });
        BuiltinPlugins.install(modBus);
        io.github.izakyl.folkways.vanilla.toms.TomsOrdersPlugin.install(modBus);
        if (OptionalIntegrations.TACZ.enabled()) {
            TaczResidentIntegration.install(modBus, container);
        }
        if (OptionalIntegrations.SUPERB_WARFARE.enabled()) {
            WarfareResidentIntegration.install(modBus);
        }
        if (OptionalIntegrations.COOKERY.enabled()) {
            CookeryPlugin.install(modBus);
        }
        if (OptionalIntegrations.CARTS.enabled()) {
            CartsPlugin.install(modBus);
        }
        if (OptionalIntegrations.EPIC_FIGHT.enabled()) {
            ResidentEpicFightIntegration.install(modBus);
        }
    }
}
