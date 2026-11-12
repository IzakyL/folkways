package io.github.izakyl.folkways.core.shell.mixin;

import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEKey;
import appeng.menu.me.common.MEStorageMenu;
import io.github.izakyl.folkways.core.shell.ae2.TerminalRecipes;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MEStorageMenu.class, remap = false)
public abstract class Ae2TerminalMenuMixin {

    @Shadow
    public abstract IGridNode getGridNode();

    @Inject(method = "getCraftablesFromGrid", at = @At("RETURN"), cancellable = true)
    private void folkways$filter(CallbackInfoReturnable<Set<AEKey>> callback) {
        var menu = (MEStorageMenu) (Object) this;
        if (menu.getPlayer() instanceof ServerPlayer player) {
            callback.setReturnValue(TerminalRecipes.filter(player, getGridNode(), callback.getReturnValue()));
        }
    }
}
