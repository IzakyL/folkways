package io.github.izakyl.folkways.plugins.cookery.steamer.mixin;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.SteamerBlockEntity;
import io.github.izakyl.folkways.plugins.cookery.steamer.SteamerStation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SteamerBlockEntity.class, remap = false)
public abstract class SteamerBlockEntityMixin {
    @Shadow private int litLevel;

    // Native updateLitLevel leaves its old value when a heat source is removed.
    // Only an intact Folkways batch uses this correction; native cooldown still runs itself.
    @Inject(method = "tick", at = @At("HEAD"))
    private void folkways$forgetRemovedHeat(Level level, CallbackInfo ci) {
        if (level instanceof ServerLevel server && SteamerStation.ownedWithoutHeat((SteamerBlockEntity) (Object) this, server)) {
            litLevel = 0;
        }
    }
}
