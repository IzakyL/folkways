package io.github.izakyl.folkways.vanilla.cookery.stockpot.mixin;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = StockpotBlockEntity.class, remap = false)
public interface StockpotTimer {
    @Accessor("currentTick")
    int folkways$cookingTicks();
}
