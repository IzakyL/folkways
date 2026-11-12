package io.github.izakyl.folkways.core.shell.curios;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import top.theillusivec4.curios.client.render.CuriosLayer;

/** Curios' player layer: worn and cosmetic stacks, per-slot render toggles, {@code CuriosRendererRegistry}. */
@OnlyIn(Dist.CLIENT)
public final class CuriosLayers {
    private CuriosLayers() {
    }

    public static <T extends LivingEntity, M extends HumanoidModel<T>> RenderLayer<T, M> worn(
            RenderLayerParent<T, M> parent) {
        return new CuriosLayer<>(parent);
    }
}
