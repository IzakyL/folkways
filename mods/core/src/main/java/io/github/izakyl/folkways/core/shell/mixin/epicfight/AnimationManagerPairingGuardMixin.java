package io.github.izakyl.folkways.core.shell.mixin.epicfight;

import io.github.izakyl.folkways.core.shell.epicfight.client.AnimationPairingReloadGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.animation.AnimationManager;
import yesman.epicfight.network.server.SPDatapackSync;

@Mixin(value = AnimationManager.class, remap = false)
public abstract class AnimationManagerPairingGuardMixin {
    @Inject(method = "processServerPacket(Lyesman/epicfight/network/server/SPDatapackSync;Z)V",
        at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void folkways$waitForActualClientReload(SPDatapackSync packet, boolean mandatory,
            CallbackInfo callback) {
        if (AnimationPairingReloadGuard.beforeProcess((AnimationManager) (Object) this, packet, mandatory)) {
            callback.cancel();
        }
    }
}
