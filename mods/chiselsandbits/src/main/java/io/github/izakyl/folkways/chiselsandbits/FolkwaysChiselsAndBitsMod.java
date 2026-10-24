package io.github.izakyl.folkways.chiselsandbits;

import io.github.izakyl.folkways.core.api.structure.StructureCompat;
import mod.chiselsandbits.block.ChiseledBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(FolkwaysChiselsAndBitsMod.ID)
public final class FolkwaysChiselsAndBitsMod {
    public static final String ID = "folkways_chiselsandbits";

    public FolkwaysChiselsAndBitsMod(IEventBus bus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, CandBConfig.SPEC);
        bus.addListener(this::setup);
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) bus.addListener(CandBModels::setup);
    }

    private void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            CandBSchematicBridge bridge = new CandBSchematicBridge();
            BuiltInRegistries.BLOCK.stream().filter(block -> block instanceof ChiseledBlock)
                .forEach(block -> StructureCompat.register(block, bridge));
            if (ModList.get().isLoaded("create")) {
                CreateBridge.install(bridge);
            }
        });
    }
}
