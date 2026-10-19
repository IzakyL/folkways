package io.github.izakyl.folkways.cookery.spit.mixin;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ShawarmaSpitBlockEntity;
import io.github.izakyl.folkways.cookery.spit.SpitSafety;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ShawarmaSpitBlockEntity.class, remap = false)
public abstract class ShawarmaSpitBlockEntityMixin {
    @Redirect(method = "giveItem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean folkways$bookedKnifeGrip(LivingEntity user, DamageSource source, float damage) {
        return !SpitSafety.protectedBurn((ShawarmaSpitBlockEntity) (Object) this, user, source) && user.hurt(source, damage);
    }
}
