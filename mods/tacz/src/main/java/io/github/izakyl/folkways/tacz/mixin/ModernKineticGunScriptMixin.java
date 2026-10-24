package io.github.izakyl.folkways.tacz.mixin;

import com.tacz.guns.item.ModernKineticGunScriptAPI;
import com.tacz.guns.util.CycleTaskHelper;
import io.github.izakyl.folkways.tacz.TaczDischargeGuard;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ModernKineticGunScriptAPI.class, remap = false)
public abstract class ModernKineticGunScriptMixin {
    @Shadow private LivingEntity shooter;
    @Shadow private ItemStack itemStack;

    @Redirect(method = "shootOnce", at = @At(value = "INVOKE",
        target = "Lcom/tacz/guns/util/CycleTaskHelper;addCycleTask(Ljava/util/function/BooleanSupplier;JI)V"))
    private void folkways$guardBurst(BooleanSupplier task, long period, int cycles) {
        CycleTaskHelper.addCycleTask(TaczDischargeGuard.capture(shooter, itemStack, task), period, cycles);
    }
}
