package io.github.izakyl.folkways.cookery.tray.mixin;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.BambooTrayBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = BambooTrayBlockEntity.class, remap = false)
public interface BambooTrayAccess {
    @Invoker("hasDryingExposure")
    static boolean folkways$exposure(Level level, BlockPos pos) { throw new AssertionError(); }
    @Invoker("hasWaterDripstone")
    static boolean folkways$dripstone(Level level, BlockPos pos) { throw new AssertionError(); }
}
