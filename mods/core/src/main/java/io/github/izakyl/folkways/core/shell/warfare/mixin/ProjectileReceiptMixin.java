package io.github.izakyl.folkways.core.shell.warfare.mixin;

import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.izakyl.folkways.core.combat.api.ColonyDamage;
import io.github.izakyl.folkways.core.shell.warfare.WarfareWeapon;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GunItem.class)
public abstract class ProjectileReceiptMixin {

    @WrapOperation(
        method = "shootBullet(Lcom/atsuishio/superbwarfare/data/gun/ShootParameters;)Z",
        at =
        @At(
        value = "INVOKE",
        target =
        "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean folkways$receipt(ServerLevel level, Entity projectile, Operation<Boolean> original,
                                     ShootParameters parameters) {
        if (parameters.ammoSupplier instanceof VehicleEntity vehicle) {
            ColonyDamage.party(vehicle).or(() -> ColonyDamage.party(parameters.shooter))
                .ifPresent(id -> ColonyDamage.mark(projectile, id));
        }
        return WarfareWeapon.emitted(original.call(level, projectile));
    }
}
