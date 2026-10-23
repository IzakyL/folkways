package io.github.izakyl.folkways.cookery.dining.mixin;

import com.github.ysbbbbbb.kaleidoscopecookery.client.render.block.TableBlockEntityRender;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.izakyl.folkways.cookery.dining.TableFoodModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(TableBlockEntityRender.class)
public abstract class TableBlockEntityRenderMixin {
    @Redirect(method = "render(Lcom/github/ysbbbbbb/kaleidoscopecookery/blockentity/decoration/TableBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderStatic(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;IILcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;I)V"))
    private void folkways$renderPlacedDish(ItemRenderer renderer, ItemStack stack, ItemDisplayContext context,
                                         int light, int overlay, PoseStack pose, MultiBufferSource buffers,
                                         Level level, int seed) {
        var dish = TableFoodModels.placedDish(stack);
        if (dish == null) {
            renderer.renderStatic(stack, context, light, overlay, pose, buffers, level, seed);
            return;
        }
        var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(dish);
        // FIXED is an item-frame presentation: some native dishes rotate it upright or enlarge it.
        // Keep the actual placed geometry horizontal and use the table's half-size dish footprint.
        pose.pushPose();
        pose.scale(0.5F, 0.5F, 0.5F);
        renderer.render(stack, ItemDisplayContext.NONE, false, pose, buffers, light, overlay, model);
        pose.popPose();
    }
}
