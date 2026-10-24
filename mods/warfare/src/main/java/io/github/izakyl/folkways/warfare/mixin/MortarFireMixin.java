package io.github.izakyl.folkways.warfare.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import io.github.izakyl.folkways.warfare.WarfareStations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MortarEntity.class)
public abstract class MortarFireMixin {
    @Inject(method = "baseTick", at = @At("HEAD"))
    private void folkways$checkPendingShell(CallbackInfo callback) {
        MortarEntity mortar = (MortarEntity) (Object) this;
        if (!mortar.level().isClientSide() && mortar.getEntityData().get(MortarEntity.FIRE_TIME) == 6
                && !WarfareStations.permitsMortar(mortar)) { mortar.getEntityData().set(MortarEntity.FIRE_TIME, 0); }
    }
}
