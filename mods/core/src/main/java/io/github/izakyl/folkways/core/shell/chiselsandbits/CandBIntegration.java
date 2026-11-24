package io.github.izakyl.folkways.core.shell.chiselsandbits;

import io.github.izakyl.folkways.core.work.api.StructureCompat;
import io.github.izakyl.folkways.core.work.api.Voxelizer;
import mod.chiselsandbits.block.ChiseledBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

public final class CandBIntegration {
    public static final String CONFIG = "folkways_core-chiselsandbits-server.toml";

    private CandBIntegration() {
    }

    public static void install(IEventBus bus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, CandBConfig.SPEC, CONFIG);
        bus.addListener(CandBIntegration::setup);
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            bus.addListener(CandBModels::setup);
        }
    }

    private static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            Voxelizer.register(new CandBVoxelizer());
            CandBSchematicBridge bridge = new CandBSchematicBridge();
            BuiltInRegistries.BLOCK.stream().filter(block -> block instanceof ChiseledBlock)
                .forEach(block -> StructureCompat.register(block, bridge));
        });
    }
}
