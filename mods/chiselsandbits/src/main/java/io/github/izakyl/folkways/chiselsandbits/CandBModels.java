package io.github.izakyl.folkways.chiselsandbits;

import io.github.izakyl.folkways.front.api.StructureModels;
import java.util.LinkedHashMap;
import mod.chiselsandbits.utils.MultiStateSnapshotUtils;
import mod.chiselsandbits.block.ChiseledBlock;
import mod.chiselsandbits.client.model.baked.chiseled.ChiselRenderType;
import mod.chiselsandbits.client.model.baked.chiseled.ChiseledBlockBakedModelManager;
import mod.chiselsandbits.client.model.baked.simple.CombinedModel;
import mod.chiselsandbits.client.util.BlockInformationUtils;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

final class CandBModels {
    private CandBModels() {
    }

    static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> BuiltInRegistries.BLOCK.stream().filter(block -> block instanceof ChiseledBlock)
            .forEach(block -> StructureModels.register(block, (view, ghost) -> {
                var storage = CandBStructureCodec.decode(ghost.payload().tag());
                var counts = CandBStructureCodec.counts(storage);
                var primary = counts.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).orElseThrow().getKey();
                var snapshot = MultiStateSnapshotUtils.createFromStorage(storage);
                var models = new LinkedHashMap<RenderType, BakedModel>();
                var manager = ChiseledBlockBakedModelManager.getInstance();
                for (RenderType layer : BlockInformationUtils.extractRenderTypes(counts.keySet())) {
                    models.put(layer, new CombinedModel(
                        manager.get(snapshot, primary, ChiselRenderType.fromLayer(layer, false), layer),
                        manager.get(snapshot, primary, ChiselRenderType.fromLayer(layer, true), layer)));
                }
                return models;
            })));
    }
}
