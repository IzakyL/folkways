package io.github.izakyl.folkways.war.mixin;

import io.github.izakyl.folkways.war.AttributedFires;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class FireRemovalMixin {
    @Inject(method = "onRemove", at = @At("HEAD"))
    private void folkways$forgetFire(Level level, BlockPos at, BlockState next, boolean moving, CallbackInfo callback) {
        if (((BlockState) (Object) this).is(Blocks.FIRE) && !next.is(Blocks.FIRE) && level instanceof ServerLevel server) { AttributedFires.of(server).forget(at); }
    }
}
