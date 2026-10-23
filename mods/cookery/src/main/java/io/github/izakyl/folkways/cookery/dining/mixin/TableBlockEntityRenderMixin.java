package io.github.izakyl.folkways.cookery.dining.mixin;

import com.github.ysbbbbbb.kaleidoscopecookery.client.render.block.TableBlockEntityRender;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.decoration.TableBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.izakyl.folkways.cookery.dining.TableFoodModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(TableBlockEntityRender.class)
public abstract class TableBlockEntityRenderMixin {
    @Redirect(method = "render(Lcom/github/ysbbbbbb/kaleidoscopecookery/blockentity/decoration/TableBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At(value = "INVOKE", target = "Lcom/github/ysbbbbbb/kaleidoscopecookery/blockentity/decoration/TableBlockEntity;getItems()Lnet/neoforged/neoforge/items/ItemStackHandler;"))
    private ItemStackHandler folkways$visibleServings(TableBlockEntity table) {
        // The native renderer counts nonempty slots, then draws only slots [0, count).
        // Eating can leave holes: compact the rendering view without moving real inventory slots.
        var items = table.getItems();
        boolean gap = false;
        boolean sparse = false;
        for (int slot = 0; slot < items.getSlots(); slot++) {
            if (items.getStackInSlot(slot).isEmpty()) { gap = true; }
            else if (gap) { sparse = true; break; }
        }
        if (!sparse) { return items; }
        var visible = new ItemStackHandler(items.getSlots());
        int next = 0;
        for (int slot = 0; slot < items.getSlots(); slot++) {
            var stack = items.getStackInSlot(slot);
            if (!stack.isEmpty()) { visible.setStackInSlot(next++, stack); }
        }
        return visible;
    }

    @Redirect(method = "render(Lcom/github/ysbbbbbb/kaleidoscopecookery/blockentity/decoration/TableBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderStatic(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;IILcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;I)V"))
    private void folkways$renderPlacedDish(ItemRenderer renderer, ItemStack stack, ItemDisplayContext context,
                                         int light, int overlay, PoseStack pose, MultiBufferSource buffers,
                                         Level level, int seed) {
        var dish = TableFoodModels.placedDish(stack);
        if (dish == null) {
            if (stack.getFoodProperties(null) == null || stack.getItem() instanceof BlockItem || stack.getUseAnimation() == UseAnim.DRINK) {
                renderer.renderStatic(stack, context, light, overlay, pose, buffers, level, seed);
                return;
            }
            // Most Cookery recipes only supply a generated inventory icon. The native FIXED
            // presentation stands those upright like item frames; lay them on the tabletop.
            pose.pushPose();
            pose.translate(0, -0.47, 0);
            pose.mulPose(Axis.XP.rotationDegrees(90));
            renderer.renderStatic(stack, ItemDisplayContext.NONE, light, overlay, pose, buffers, level, seed);
            pose.popPose();
            return;
        }
        var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(dish);
        // Use Cookery's placed geometry: its item model can be only a generated inventory icon.
        // The native table already scales every serving to 0.65; do not halve it again.
        // ItemRenderer centers block geometry around -0.5. Compensate for the extra 0.25
        // downward extent versus the old half-size model so its base stays on the tabletop.
        pose.pushPose();
        pose.translate(0, 0.25, 0);
        renderer.render(stack, ItemDisplayContext.NONE, false, pose, buffers, light, overlay, model);
        pose.popPose();
    }
}
