package io.github.izakyl.folkways.core.shell.mixin;

import com.refinedmods.refinedstorage.common.api.grid.Grid;
import com.refinedmods.refinedstorage.common.grid.GridData;
import io.github.izakyl.folkways.core.shell.rs2.TerminalRecipes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GridData.class, remap = false)
public abstract class Rs2GridDataMixin {

    @Inject(method = "of", at = @At("RETURN"), cancellable = true)
    private static void folkways$filter(Grid grid, CallbackInfoReturnable<GridData> callback) {
        callback.setReturnValue(TerminalRecipes.opening(grid, callback.getReturnValue()));
    }
}
