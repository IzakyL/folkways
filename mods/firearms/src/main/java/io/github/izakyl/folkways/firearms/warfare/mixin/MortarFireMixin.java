package io.github.izakyl.folkways.firearms.warfare.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.izakyl.folkways.firearms.warfare.WarfareStations;
import io.github.izakyl.folkways.firearms.warfare.WarfareWeapon;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MortarEntity.class)
public abstract class MortarFireMixin {

    @WrapMethod(method = "baseTick")
    private void folkways$checkPendingShell(Operation<Void> original) {
        MortarEntity mortar = (MortarEntity) (Object) this;
        if (mortar.level().isClientSide()) {
            original.call();
        } else {
            WarfareStations.tickMortar(mortar, () -> original.call());
        }
    }

    @WrapOperation(
        method = "baseTick",
        at =
        @At(
        value = "INVOKE",
        target =
        "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean folkways$receipt(ServerLevel level, Entity projectile, Operation<Boolean> original) {
        return WarfareWeapon.emitted(original.call(level, projectile));
    }
}
