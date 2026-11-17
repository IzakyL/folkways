package io.github.izakyl.folkways.core.front.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.izakyl.folkways.core.front.client.Overhead;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    // Keep vanilla packet selection and its 20-tick reminder for the body behind our detached camera.
    // Restrict this to sendPosition: riding and camera-dependent movement keep their vanilla branches.
    @ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;isControlledCamera()Z"))
    private boolean folkways$sendBodyPosition(boolean controlled) {
        return controlled || Overhead.sendsPlayerPosition((LocalPlayer) (Object) this);
    }
}
