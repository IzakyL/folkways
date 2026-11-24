package io.github.izakyl.folkways.core.shell.warfare.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.izakyl.folkways.core.combat.api.ColonyDamage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MortarEntity.class)
public abstract class MortarShotMixin {

    @WrapOperation(method = "baseTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean folkways$attribute(ServerLevel level, Entity shell, Operation<Boolean> original) {
        ColonyDamage.party((MortarEntity) (Object) this).ifPresent(id -> ColonyDamage.mark(shell, id));
        return original.call(level, shell);
    }
}
