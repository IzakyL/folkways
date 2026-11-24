package io.github.izakyl.folkways.vanilla.tacz;

import io.github.izakyl.folkways.core.base.persist.upgrade.ConfigRenames;
import io.github.izakyl.folkways.core.shell.tacz.TaczAmmoInventory;
import io.github.izakyl.folkways.core.shell.tacz.TaczGuns;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

public final class TaczResidentIntegration {
    private TaczResidentIntegration() {
    }

    public static void install(IEventBus modBus, ModContainer container) {
        String config = "folkways_core-tacz-server.toml";
        ConfigRenames.register("folkways_firearms-server.toml", config);
        ConfigRenames.register("folkways-tacz-server.toml", config);
        container.registerConfig(ModConfig.Type.SERVER, TaczConfig.SPEC, config);
        TaczGuns.install(new TaczGuns.Policy() {
            @Override
            public boolean allowed(ResourceLocation gun) {
                return TaczConfig.allowed(gun);
            }

            @Override
            public double rangeCap() {
                return TaczConfig.rangeCap();
            }

            @Override
            public double range(ResourceLocation gun) {
                return TaczConfig.range(gun);
            }
        });
        if (FMLEnvironment.dist.isClient()) {
            TaczClient.install();
        }
        modBus.addListener(
            (RegisterCapabilitiesEvent event) ->
                event.registerEntity(
                    Capabilities.ItemHandler.ENTITY,
                    PersonBody.RESIDENT.get(),
                    (body, context) -> new TaczAmmoInventory(body)));
    }
}
