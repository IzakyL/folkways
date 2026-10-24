package io.github.izakyl.folkways.front.api;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Models built from configuration in the preview's isolated view. */
@OnlyIn(Dist.CLIENT)
public final class StructureModels {
    private static final Map<Block, BiFunction<BlockAndTintGetter, Ghost, Map<RenderType, BakedModel>>> MODELS =
        new IdentityHashMap<>();

    private StructureModels() {
    }

    public static void register(Block block, BiFunction<BlockAndTintGetter, Ghost, Map<RenderType, BakedModel>> factory) {
        MODELS.put(block, factory);
    }

    public static Optional<Map<RenderType, BakedModel>> of(BlockAndTintGetter view, Ghost ghost) {
        return Optional.ofNullable(MODELS.get(ghost.state().getBlock())).map(factory -> factory.apply(view, ghost));
    }
}
