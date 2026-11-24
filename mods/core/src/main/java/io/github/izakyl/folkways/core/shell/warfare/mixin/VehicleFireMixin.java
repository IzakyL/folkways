package io.github.izakyl.folkways.core.shell.warfare.mixin;

import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import io.github.izakyl.folkways.core.combat.api.WeaponHands;
import io.github.izakyl.folkways.core.shell.warfare.WarfareVehicles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GunItem.class)
public abstract class VehicleFireMixin {

    @Inject(
        method = "shoot(Lcom/atsuishio/superbwarfare/data/gun/ShootParameters;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void folkways$checkDuty(ShootParameters parameters, CallbackInfo callback) {
        if (parameters.ammoSupplier instanceof VehicleEntity vehicle
            && parameters.shooter instanceof WeaponHands
            && !WarfareVehicles.provider().permitsShot(vehicle, parameters)) {
            callback.cancel();
        }
    }
}
