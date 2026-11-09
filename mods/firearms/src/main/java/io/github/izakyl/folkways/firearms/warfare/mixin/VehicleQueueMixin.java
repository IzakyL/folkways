package io.github.izakyl.folkways.firearms.warfare.mixin;

import com.atsuishio.superbwarfare.Mod;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.izakyl.folkways.firearms.warfare.WarfareStations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(VehicleEntity.class)
public abstract class VehicleQueueMixin {

    @WrapOperation(
        method =
        "vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;"
        + "Ljava/lang/String;Ljava/util/UUID;Lnet/minecraft/world/phys/Vec3;)V",
        at =
        @At(
        value = "INVOKE",
        target =
        "Lcom/atsuishio/superbwarfare/Mod$Companion;queueServerWork(ILjava/lang/Runnable;)Z"))
    private boolean folkways$bindShot(
        Mod.Companion companion, int delay, Runnable action, Operation<Boolean> original) {
        return original.call(companion, delay, WarfareStations.queued((VehicleEntity) (Object) this, action));
    }
}
