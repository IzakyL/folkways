package io.github.izakyl.folkways.tacz.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tacz.guns.item.ModernKineticGunScriptAPI;
import io.github.izakyl.folkways.tacz.TaczDischargeGuard;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ModernKineticGunScriptAPI.class, remap = false)
public abstract class ModernKineticGunScriptMixin {

    @Shadow private LivingEntity shooter;
    @Shadow private ItemStack itemStack;

    @WrapOperation(
        method = "shootOnce",
        at =
        @At(
        value = "INVOKE",
        target =
        "Lcom/tacz/guns/util/CycleTaskHelper;addCycleTask(Ljava/util/function/BooleanSupplier;JI)V"))
    private void folkways$guardBurst(BooleanSupplier task, long period, int cycles, Operation<Void> original) {
        original.call(TaczDischargeGuard.capture(shooter, itemStack, task), period, cycles);
    }
}
