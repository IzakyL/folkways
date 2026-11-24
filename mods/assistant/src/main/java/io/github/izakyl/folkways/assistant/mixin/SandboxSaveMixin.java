package io.github.izakyl.folkways.assistant.mixin;

import io.github.izakyl.folkways.assistant.sandbox.Sandbox;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The sandbox is never saved, not even by a forced save such as {@code /save-all}, which writes levels marked
 * {@code noSave} too: a copy saved mid-probe, a ship or a walker among them, would come back in the next probe.
 */
@Mixin(ServerLevel.class)
public abstract class SandboxSaveMixin {

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void folkways$neverSaveSandbox(ProgressListener progress, boolean flush, boolean skip, CallbackInfo info) {
        if (Sandbox.is((ServerLevel) (Object) this)) {
            info.cancel();
        }
    }
}
