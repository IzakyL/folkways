package io.github.izakyl.folkways.create;

import io.github.izakyl.folkways.effect.perk.PerkPool;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

@Mod(FolkwaysCreateMod.ID)
public final class FolkwaysCreateMod {

    public static final String ID = "folkways_create";

    public FolkwaysCreateMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, FolkwaysCreateConfig.SPEC);
        // The quick hands read their ladders from this config, so its loading grants them again.
        modBus.addListener(ModConfigEvent.Loading.class, event -> PerkPool.regrant());
        modBus.addListener(ModConfigEvent.Reloading.class, event -> PerkPool.regrant());
        CreatePlugins.install(modBus);
    }
}
