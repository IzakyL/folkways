package io.github.izakyl.folkways.plugins.cookery.tray.mixin;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.BambooTrayBlockEntity;
import io.github.izakyl.folkways.plugins.cookery.tray.TrayWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = BambooTrayBlockEntity.class, remap = false)
public abstract class BambooTrayBlockEntityMixin {
    @Redirect(method = "serverTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isRainingAt(Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean folkways$projectRain(Level queried, BlockPos query, Level level, BlockPos at,
                                               BlockState state, BambooTrayBlockEntity tray) {
        return TrayWeather.raining(queried, query, tray);
    }
    @Redirect(method = "serverTick", at = @At(value = "INVOKE", target = "Lcom/github/ysbbbbbb/kaleidoscopecookery/blockentity/kitchen/BambooTrayBlockEntity;hasDryingExposure(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean folkways$projectExposure(Level queried, BlockPos query, Level level, BlockPos at,
                                                   BlockState state, BambooTrayBlockEntity tray) {
        return TrayWeather.exposure(queried, query, tray);
    }
}
