package io.github.izakyl.folkways.core.shell.mixin;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.layout.LayoutProperties;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import io.github.izakyl.folkways.front.ui.screen.ColonyShell;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = UIElement.class, remap = false)
public abstract class ColonyDisplayMixin {

    @Inject(method = "isDisplayed", at = @At("HEAD"), cancellable = true)
    private void folkways$serverDisplay(CallbackInfoReturnable<Boolean> callback) {
        UIElement element = (UIElement) (Object) this;
        var ui = element.getModularUI();
        if (ui != null && ui.player instanceof ServerPlayer
                && ui.getMenu() instanceof ModularUIContainerMenu menu && menu.uiHolder instanceof ColonyShell) {
            // The server never computes Taffy styles. Read the cascade without running client layout code.
            callback.setReturnValue(element.getStyleBag().computeCandidate(LayoutProperties.DISPLAY)
                != TaffyDisplay.NONE);
        }
    }
}
