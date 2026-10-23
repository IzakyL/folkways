package io.github.izakyl.folkways.cookery.tavern;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TavernCounterClient {
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        if (ModList.get().isLoaded("kaleidoscope_tavern")) event.registerBlockEntityRenderer(TavernCounters.COUNTER.get(), Renderer::new);
    }
    private static final class Renderer implements BlockEntityRenderer<TavernCounterBlockEntity> {
        Renderer(BlockEntityRendererProvider.Context context) { }
        public void render(TavernCounterBlockEntity counter, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            for (int slot = 0; slot < counter.getContainerSize(); slot++) {
                if (counter.getItem(slot).isEmpty()) continue;
                pose.pushPose();
                pose.translate(slot == 0 ? .3 : .7, 1.18, .5);
                pose.scale(.5f, .5f, .5f);
                Minecraft.getInstance().getItemRenderer().renderStatic(counter.getItem(slot), ItemDisplayContext.FIXED, light, overlay,
                    pose, buffers, counter.getLevel(), slot);
                pose.popPose();
            }
        }
    }
}
