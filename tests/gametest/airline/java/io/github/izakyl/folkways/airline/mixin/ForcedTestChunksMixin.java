package io.github.izakyl.folkways.airline.mixin;

import io.github.izakyl.folkways.airline.AirlineTestArea;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records newly acquired tickets and entities inside a live test's region. */
@Mixin(ServerLevel.class)
public abstract class ForcedTestChunksMixin {
    @Inject(method = "addFreshEntity", at = @At("RETURN"))
    private void airline$ownEntity(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValue()) {
            AirlineTestArea.spawned((ServerLevel) (Object) this, entity);
        }
    }

    @Inject(method = "setChunkForced", at = @At("HEAD"))
    private void airline$ownTicket(int x, int z, boolean forced, CallbackInfoReturnable<Boolean> callback) {
        if (forced) {
            AirlineTestArea.forced((ServerLevel) (Object) this, x, z);
        }
    }
}
