package io.github.izakyl.folkways.core.shell.mixin;

import io.github.izakyl.folkways.core.engine.colony.ColonyMemberWrites;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {

    @Inject(
        method = "blockEntityChanged(Lnet/minecraft/core/BlockPos;)V",
        at = @At("HEAD"),
        require = 1)
    private void folkways$observeColonyMemberWrite(BlockPos pos, CallbackInfo callback) {
        ColonyMemberWrites.observe((Level) (Object) this, pos);
    }

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("RETURN"), require = 1)
    private void folkways$observeBlock(BlockPos pos, BlockState state,
            int flags, int recursion, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ()) {
            ColonyMemberWrites.blockChanged((Level) (Object) this, pos);
        }
    }
}
