package io.github.izakyl.folkways.airline.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** GameTest-only: shutdown resets ServerLevel.noSave, including for disposable survey terrain. */
@Mixin(ChunkMap.class)
public abstract class DisposableChunksMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void airline$discardDisposableTerrain(ChunkAccess chunk, CallbackInfoReturnable<Boolean> callback) {
        if (level.getServer() instanceof GameTestServer) {
            chunk.setUnsaved(false);
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "saveAllChunks", at = @At("HEAD"), cancellable = true)
    private void airline$skipDisposableTerrain(boolean flush, CallbackInfo callback) {
        if (level.getServer() instanceof GameTestServer) {
            callback.cancel();
        }
    }
}
