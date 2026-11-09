package io.github.izakyl.folkways.firearms.warfare.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.Fh77bwEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.izakyl.folkways.vanilla.combat.WeaponStations;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ArtilleryEntity.class, remap = false)
public abstract class ArtilleryAimSourceMixin {

    // Keep the owned station's aim from being replaced by its passenger's idle head pitch.
    @WrapOperation(method = "baseTick", at = @At(value = "INVOKE",
        target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/ArtilleryEntity;"
            + "setShootVec(Lorg/joml/Vector3f;)V"))
    private void folkways$retainStationAim(ArtilleryEntity artillery, Vector3f direction, Operation<Void> original) {
        if (!artillery.level().isClientSide() && artillery instanceof Fh77bwEntity
            && artillery.getNthEntity(artillery.getTurretControllerIndex()) instanceof ResidentEntity resident
            && WeaponStations.ownsSeat(resident)) {
            return;
        }
        original.call(artillery, direction);
    }
}
