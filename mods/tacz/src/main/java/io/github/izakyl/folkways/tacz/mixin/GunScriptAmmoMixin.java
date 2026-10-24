package io.github.izakyl.folkways.tacz.mixin;

import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.item.ModernKineticGunScriptAPI;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Each native script invocation may load only rounds it actually extracted or moved out of the magazine. */
@Mixin(value = ModernKineticGunScriptAPI.class, remap = false)
public abstract class GunScriptAmmoMixin {
    @Shadow private LivingEntity shooter;
    @Shadow private ItemStack itemStack;
    @Shadow private AbstractGunItem abstractGunItem;
    @Unique private int folkways$rounds;
    @Unique private int folkways$magazineBefore;
    @Unique private int folkways$blocked;

    @Inject(method = {"consumeAmmoFromPlayer", "removeAmmoFromMagazine"}, at = @At("RETURN"))
    private void folkways$creditActualRounds(int amount, CallbackInfoReturnable<Integer> result) {
        if (shooter instanceof ResidentEntity) { folkways$rounds += Math.max(0, result.getReturnValueI()); }
    }

    @ModifyVariable(method = "putAmmoInMagazine", at = @At("HEAD"), argsOnly = true)
    private int folkways$boundMagazine(int requested) {
        if (!(shooter instanceof ResidentEntity)) { return requested; }
        folkways$magazineBefore = abstractGunItem.getCurrentAmmoCount(itemStack);
        int allowed = Math.min(Math.max(0, requested), folkways$rounds);
        folkways$blocked = Math.max(0, requested - allowed);
        return requested < 0 ? requested : allowed;
    }

    @Inject(method = "putAmmoInMagazine", at = @At("RETURN"), cancellable = true)
    private void folkways$debitLoadedRounds(int amount, CallbackInfoReturnable<Integer> result) {
        if (!(shooter instanceof ResidentEntity)) { return; }
        folkways$rounds -= Math.max(0, abstractGunItem.getCurrentAmmoCount(itemStack) - folkways$magazineBefore);
        result.setReturnValue(result.getReturnValueI() + folkways$blocked);
    }

    @ModifyVariable(method = "setAmmoInBarrel", at = @At("HEAD"), argsOnly = true)
    private boolean folkways$boundChamber(boolean loaded) {
        if (!(shooter instanceof ResidentEntity) || !loaded || abstractGunItem.hasBulletInBarrel(itemStack)) { return loaded; }
        if (folkways$rounds == 0) { return false; }
        folkways$rounds--;
        return true;
    }
}
