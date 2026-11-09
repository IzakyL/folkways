package io.github.izakyl.folkways.epicfight.mixin;

import io.github.izakyl.folkways.epicfight.client.AnimationPairingReloadGuard;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftReloadPairingGuardMixin {
    @Inject(method = "reloadResourcePacks(ZLnet/minecraft/client/Minecraft$GameLoadCookie;)"
        + "Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"), require = 1)
    private void folkways$observeActualReload(CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        AnimationPairingReloadGuard.observe(callback.getReturnValue());
    }
}
