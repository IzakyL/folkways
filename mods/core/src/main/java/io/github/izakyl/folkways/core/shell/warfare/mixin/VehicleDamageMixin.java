package io.github.izakyl.folkways.core.shell.warfare.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.combat.api.ColonyDamage;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VehicleEntity.class)
public abstract class VehicleDamageMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void folkways$protectColony(DamageSource source, float amount, CallbackInfoReturnable<Boolean> result) {
        if (ColonyDamage.friendly((Entity) (Object) this, source)) {
            result.setReturnValue(false);
        }
    }
}
