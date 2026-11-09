package io.github.izakyl.folkways.vanilla;

import io.github.izakyl.folkways.core.effect.perk.PerkPool;
import io.github.izakyl.folkways.vanilla.upgrade.VanillaUpgrades;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

// Folkways Vanilla: the work residents do in a world of vanilla blocks, installed over Folkways Core.
@Mod(FolkwaysVanillaMod.ID)
public final class FolkwaysVanillaMod {

    public static final String ID = "folkways_vanilla";

    public FolkwaysVanillaMod(IEventBus modBus, ModContainer container) {
        VanillaUpgrades.register();
        container.registerConfig(ModConfig.Type.SERVER, FolkwaysVanillaConfig.SPEC);
        // The quick hands read their ladders from this config, so its loading grants them again.
        modBus.addListener(ModConfigEvent.Loading.class, event -> PerkPool.regrant());
        modBus.addListener(ModConfigEvent.Reloading.class, event -> PerkPool.regrant());
        BuiltinPlugins.install(modBus);
    }
}
