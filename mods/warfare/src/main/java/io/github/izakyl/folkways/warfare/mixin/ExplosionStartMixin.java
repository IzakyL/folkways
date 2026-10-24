package io.github.izakyl.folkways.warfare.mixin;

import com.atsuishio.superbwarfare.tools.CustomExplosion;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.izakyl.folkways.warfare.WarfareExplosions;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CustomExplosion.Builder.class)
public abstract class ExplosionStartMixin {
    @WrapOperation(method = "explode", at = @At(value = "INVOKE",
        target = "Lnet/neoforged/neoforge/event/EventHooks;onExplosionStart(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/Explosion;)Z"))
    private boolean folkways$doNotRepeatStart(Level level, Explosion explosion, Operation<Boolean> original) {
        return explosion instanceof CustomExplosion custom && WarfareExplosions.residentShot(custom)
            ? false : original.call(level, explosion);
    }
}
