package io.github.izakyl.folkways.plugins.cookery.oil;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.EnamelBasinBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.item.KitchenShovelItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

/** The native hard-Player basin interaction, limited to its real lid, oil and kitchen-shovel states. */
public final class EnamelOil {
    private EnamelOil() { }

    public static boolean interact(ServerLevel level, BlockPos at, ItemStack held) {
        if (!level.isLoaded(at)) { return false; }
        var state = level.getBlockState(at);
        if (!state.is(ModBlocks.ENAMEL_BASIN.get()) || held.is(net.minecraft.world.item.Items.STICK)) { return false; }
        if (state.getValue(EnamelBasinBlock.HAS_LID)) {
            if (!level.setBlockAndUpdate(at, state.setValue(EnamelBasinBlock.HAS_LID, false))) { return false; }
            level.playSound(null, at, SoundEvents.LANTERN_BREAK, SoundSource.BLOCKS, 0.8F, 0.8F);
            return true;
        }
        if (held.isEmpty()) {
            if (!level.setBlockAndUpdate(at, state.setValue(EnamelBasinBlock.HAS_LID, true))) { return false; }
            level.playSound(null, at, SoundEvents.LANTERN_BREAK, SoundSource.BLOCKS, 0.8F, 0.4F);
            return true;
        }
        int count = state.getValue(EnamelBasinBlock.OIL_COUNT);
        if (held.is(ModItems.OIL.get())) {
            int used = Math.min(EnamelBasinBlock.MAX_OIL_COUNT - count, held.getCount());
            if (used <= 0 || !level.setBlockAndUpdate(at, state.setValue(EnamelBasinBlock.OIL_COUNT, count + used))) { return false; }
            held.shrink(used);
            level.playSound(null, at, SoundEvents.HONEY_BLOCK_BREAK, SoundSource.BLOCKS, 0.8F, 0.8F);
            return true;
        }
        if (!held.is(ModItems.KITCHEN_SHOVEL.get())) { return false; }
        boolean oily = KitchenShovelItem.hasOil(held);
        if (oily && count == EnamelBasinBlock.MAX_OIL_COUNT || !oily && count == 0) { return false; }
        if (!level.setBlockAndUpdate(at, state.setValue(EnamelBasinBlock.OIL_COUNT, count + (oily ? 1 : -1)))) { return false; }
        KitchenShovelItem.setHasOil(held, !oily);
        level.playSound(null, at, SoundEvents.HONEY_BLOCK_BREAK, SoundSource.BLOCKS, 0.8F, oily ? 0.8F : 1.2F);
        return true;
    }
}
