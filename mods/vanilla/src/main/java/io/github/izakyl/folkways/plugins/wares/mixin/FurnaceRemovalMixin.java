package io.github.izakyl.folkways.plugins.wares.mixin;

import io.github.izakyl.folkways.plugins.wares.FurnaceTraining;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forget training immediately when a hopper or player removes input, even if replaced before the next tick. */
@Mixin(BaseContainerBlockEntity.class)
public abstract class FurnaceRemovalMixin {
    @Inject(method = "removeItem", at = @At("RETURN"))
    private void folkways$removed(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        if (slot == 0 && (Object) this instanceof FurnaceTraining training) { training.folkways$removed(); }
    }

    @Inject(method = "removeItemNoUpdate", at = @At("RETURN"))
    private void folkways$taken(int slot, CallbackInfoReturnable<ItemStack> cir) {
        if (slot == 0 && (Object) this instanceof FurnaceTraining training) { training.folkways$removed(); }
    }

    @Inject(method = "clearContent", at = @At("TAIL"))
    private void folkways$cleared(CallbackInfo ci) {
        if ((Object) this instanceof FurnaceTraining training) { training.folkways$removed(); }
    }
}
