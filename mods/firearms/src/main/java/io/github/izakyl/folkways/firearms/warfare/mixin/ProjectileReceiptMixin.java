package io.github.izakyl.folkways.firearms.warfare.mixin;

import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.izakyl.folkways.firearms.warfare.WarfareWeapon;
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
    private boolean folkways$receipt(ServerLevel level, Entity projectile, Operation<Boolean> original) {
        return WarfareWeapon.emitted(original.call(level, projectile));
    }
}
