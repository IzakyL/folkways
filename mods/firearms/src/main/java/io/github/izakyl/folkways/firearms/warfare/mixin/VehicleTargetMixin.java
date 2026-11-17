package io.github.izakyl.folkways.firearms.warfare.mixin;

import com.atsuishio.superbwarfare.event.LivingEventHandler;
import io.github.izakyl.folkways.firearms.warfare.WarfareVehicles;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LivingEventHandler.class, remap = false)
public abstract class VehicleTargetMixin {

    // Clearing Mob targets prevents native auto-fire; it must not erase our independent aiming target.
    @Inject(method = "onLivingChangeTargetEvent", at = @At("HEAD"), cancellable = true)
    private void folkways$retainCrewAim(LivingChangeTargetEvent event, CallbackInfo callback) {
        if (WarfareVehicles.ownsAim(event.getEntity())) {
            callback.cancel();
        }
    }
}
