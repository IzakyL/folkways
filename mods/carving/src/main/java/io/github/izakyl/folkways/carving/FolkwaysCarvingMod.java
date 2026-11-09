package io.github.izakyl.folkways.carving;

import io.github.izakyl.folkways.carving.upgrade.CarvingUpgrades;
import io.github.izakyl.folkways.core.base.persist.upgrade.ConfigRenames;
import io.github.izakyl.folkways.core.work.api.StructureCompat;
import io.github.izakyl.folkways.core.work.api.Voxelizer;
import mod.chiselsandbits.block.ChiseledBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(FolkwaysCarvingMod.ID)
public final class FolkwaysCarvingMod {
    public static final String ID = "folkways_carving";

    public FolkwaysCarvingMod(IEventBus bus, ModContainer container) {
        CarvingUpgrades.register();
        ConfigRenames.register("folkways_chiselsandbits-server.toml", "folkways_carving-server.toml");
        container.registerConfig(ModConfig.Type.SERVER, CandBConfig.SPEC);
        bus.addListener(this::setup);
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            bus.addListener(CandBModels::setup);
        }
    }

    private void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            Voxelizer.register(new CandBVoxelizer());
            CandBSchematicBridge bridge = new CandBSchematicBridge();
            BuiltInRegistries.BLOCK.stream().filter(block -> block instanceof ChiseledBlock)
                .forEach(block -> StructureCompat.register(block, bridge));
        });
    }
}
