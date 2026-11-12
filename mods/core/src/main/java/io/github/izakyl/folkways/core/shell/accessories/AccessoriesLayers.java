package io.github.izakyl.folkways.core.shell.accessories;

import io.wispforest.accessories.client.AccessoriesRenderLayer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Accessories' entity layer: containers, cosmetics, per-slot visibility, {@code AccessoriesRendererRegistry}. */
@OnlyIn(Dist.CLIENT)
public final class AccessoriesLayers {
    private AccessoriesLayers() {
    }

    public static <T extends LivingEntity, M extends HumanoidModel<T>> RenderLayer<T, M> worn(
            RenderLayerParent<T, M> parent) {
        return new AccessoriesRenderLayer<>(parent);
    }
}
