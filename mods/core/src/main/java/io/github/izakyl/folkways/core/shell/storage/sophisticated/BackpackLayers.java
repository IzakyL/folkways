package io.github.izakyl.folkways.core.shell.storage.sophisticated;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.p3pp3rf1y.sophisticatedbackpacks.client.render.BackpackLayerRenderer;

/** Sophisticated's own worn-backpack layer; for a non-player it draws the backpack in the chest slot. */
@OnlyIn(Dist.CLIENT)
public final class BackpackLayers {
    private BackpackLayers() {
    }

    public static <T extends LivingEntity, M extends HumanoidModel<T>> RenderLayer<T, M> worn(
            RenderLayerParent<T, M> parent) {
        return new BackpackLayerRenderer<>(parent);
    }
}
