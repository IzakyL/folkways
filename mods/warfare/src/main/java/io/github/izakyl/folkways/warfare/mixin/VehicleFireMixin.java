package io.github.izakyl.folkways.warfare.mixin;

import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import io.github.izakyl.folkways.warfare.WarfareStations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GunItem.class)
public abstract class VehicleFireMixin {
    @Inject(method = "shoot(Lcom/atsuishio/superbwarfare/data/gun/ShootParameters;)V", at = @At("HEAD"), cancellable = true)
    private void folkways$checkDuty(ShootParameters parameters, CallbackInfo callback) {
        if (!WarfareStations.permits(parameters)) { callback.cancel(); }
    }
}
