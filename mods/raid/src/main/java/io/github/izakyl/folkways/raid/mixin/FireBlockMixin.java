package io.github.izakyl.folkways.raid.mixin;

import io.github.izakyl.folkways.raid.AttributedFires;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
abstract class FireBlockMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void folkways$begin(
        BlockState state, ServerLevel level, BlockPos at, RandomSource random, CallbackInfo callback) {
        if (!AttributedFires.begin(level, at)) {
            callback.cancel();
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void folkways$end(
        BlockState state, ServerLevel level, BlockPos at, RandomSource random, CallbackInfo callback) {
        AttributedFires.end(level, at);
    }

    @Redirect(
        method = "tick",
        at =
        @At(
        value = "INVOKE",
        target =
        "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPo"
        + "s;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean folkways$spread(ServerLevel level, BlockPos at, BlockState state, int flags) {
        return AttributedFires.spread(level, at, state, flags);
    }

    @Inject(
        method = "checkBurnOut",
        at =
        @At(
        value = "INVOKE",
        target =
        "Lnet/minecraft/world/level/block/state/BlockState;onCaughtFire(Lnet/minecraf"
        + "t/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Directi"
        + "on;Lnet/minecraft/world/entity/LivingEntity;)V"),
        cancellable = true)
    private void folkways$burn(
        Level level, BlockPos at, int chance, RandomSource random, int age, Direction face, CallbackInfo callback) {
        if (!AttributedFires.burn(level, at)) {
            callback.cancel();
        }
    }

    @Redirect(
        method = "checkBurnOut",
        at =
        @At(
        value = "INVOKE",
        target =
        "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/"
        + "minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean folkways$burningFire(Level level, BlockPos at, BlockState state, int flags) {
        return AttributedFires.burningFire(level, at, state, flags);
    }
}
