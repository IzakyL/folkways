package io.github.izakyl.folkways.warfare.mixin;

import com.atsuishio.superbwarfare.tools.CustomExplosion;
import io.github.izakyl.folkways.warfare.WarfareExplosions;
import java.util.Collection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CustomExplosion.class)
public abstract class CustomExplosionMixin {
    @Shadow @Final private Level level;
    @Unique private boolean folkways$cancelled;

    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void folkways$protectBeforeDamage(CallbackInfo callback) {
        if (!WarfareExplosions.permits(level, (CustomExplosion) (Object) this)) {
            folkways$cancelled = true;
            callback.cancel();
        }
    }

    @Inject(method = "explodeBlocks", at = @At("HEAD"), cancellable = true)
    private void folkways$recheckGriefing(CallbackInfo callback) {
        if (!WarfareExplosions.mayDamageBlocks(level, (CustomExplosion) (Object) this)) { callback.cancel(); }
    }

    @Inject(method = "finalizeExplosion", at = @At("HEAD"), cancellable = true)
    private void folkways$keepCancelledBlocks(boolean particles, CallbackInfo callback) {
        if (folkways$cancelled || !WarfareExplosions.mayDamageBlocks(level, (CustomExplosion) (Object) this)) { callback.cancel(); }
    }

    @ModifyVariable(method = "processBlockList", at = @At("HEAD"), argsOnly = true)
    private Collection<BlockPos> folkways$protectEachTier(Collection<BlockPos> blocks) {
        return WarfareExplosions.blocks(level, (CustomExplosion) (Object) this, blocks);
    }
}
