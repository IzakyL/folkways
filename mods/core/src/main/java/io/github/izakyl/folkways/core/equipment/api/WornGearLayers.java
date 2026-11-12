package io.github.izakyl.folkways.core.equipment.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The installed frameworks' own render layers for worn gear that a player's renderer gets and a resident renderer
 * does not: Sophisticated Backpacks' worn backpack, Curios' {@code CuriosLayer} and Accessories'
 * {@code AccessoriesRenderLayer}. Those frameworks attach them only to player skins (or to registered
 * {@code LivingEntityRenderer}s), so a resident renderer adds them itself. Each layer reads the framework's own
 * synchronized container and per-slot render toggle, and dispatches to the renderers items registered there.
 * The integrations register their layer here on the client, in the order a player's renderer has them.
 */
@OnlyIn(Dist.CLIENT)
public final class WornGearLayers {
    private static final List<Worn> WORN = new CopyOnWriteArrayList<>();

    private WornGearLayers() {
    }

    /** One framework's layer, made for whichever renderer asks. */
    public interface Worn {
        <T extends LivingEntity, M extends HumanoidModel<T>> RenderLayer<T, M> worn(RenderLayerParent<T, M> parent);
    }

    public static void register(Worn layer) {
        WORN.add(layer);
    }

    public static <T extends LivingEntity, M extends HumanoidModel<T>> List<RenderLayer<T, M>> of(
            RenderLayerParent<T, M> parent) {
        List<RenderLayer<T, M>> layers = new ArrayList<>();
        for (Worn layer : WORN) {
            layers.add(layer.worn(parent));
        }
        return List.copyOf(layers);
    }
}
