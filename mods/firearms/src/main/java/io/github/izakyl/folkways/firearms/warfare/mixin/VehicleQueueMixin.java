package io.github.izakyl.folkways.firearms.warfare.mixin;

import com.atsuishio.superbwarfare.Mod;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.izakyl.folkways.firearms.warfare.WarfareStations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.phys.Vec3;

@Mixin(VehicleEntity.class)
public abstract class VehicleQueueMixin {

    @Inject(method = "getShootVec(Ljava/lang/String;F)Lnet/minecraft/world/phys/Vec3;",
        at = @At("HEAD"), cancellable = true)
    private void folkways$mobileDirection(String weapon, float partial, CallbackInfoReturnable<Vec3> callback) {
        WarfareStations.mobileDirection((VehicleEntity) (Object) this, weapon, partial)
            .ifPresent(callback::setReturnValue);
    }

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
