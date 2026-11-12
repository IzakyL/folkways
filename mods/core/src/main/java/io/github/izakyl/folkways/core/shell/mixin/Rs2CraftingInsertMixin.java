package io.github.izakyl.folkways.core.shell.mixin;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import io.github.izakyl.folkways.core.shell.crafting.InsertionReceipts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.task.TaskImpl", remap = false)
public abstract class Rs2CraftingInsertMixin {

    @Inject(method = "beforeInsert", at = @At("RETURN"), require = 1)
    private void folkways$received(ResourceKey key, long amount, CallbackInfoReturnable<Long> result) {
        InsertionReceipts.consumed(key, result.getReturnValue());
    }
}
