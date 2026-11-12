package io.github.izakyl.folkways.core.shell.mixin;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import io.github.izakyl.folkways.core.shell.crafting.InsertionReceipts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "appeng.me.service.CraftingService", remap = false)
public abstract class Ae2CraftingInsertMixin {

    @Inject(method = "insertIntoCpus", at = @At("RETURN"), require = 1)
    private void folkways$received(AEKey key, long amount, Actionable mode, CallbackInfoReturnable<Long> result) {
        if (mode == Actionable.MODULATE) {
            InsertionReceipts.consumed(key, result.getReturnValue());
        }
    }
}
