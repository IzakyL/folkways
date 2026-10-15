package io.github.izakyl.folkways.plugins.cookery.stove;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StoveBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The native stove's player-only ignition, with the same real item costs for a resident. */
public final class StoveIgnition {
    private StoveIgnition() {
    }

    public static boolean dry(ServerLevel level, WorldPos at) {
        if (!at.in(level) || !level.isLoaded(at.block(level))) {
            return false;
        }
        return !level.isWaterAt(at.block(level).above())
            && WorldSpaces.world(level, at).map(BlockPos::containing)
                .filter(world -> !level.isRainingAt(world.above())).isPresent();
    }

    public static boolean light(ServerLevel level, WorldPos at, LivingEntity user, ItemStack ignition) {
        if (!dry(level, at) || ignition.isEmpty() || !ignition.is(TagMod.LIT_STOVE)) {
            return false;
        }
        BlockPos cell = at.block(level);
        var state = level.getBlockState(cell);
        if (!state.is(ModBlocks.STOVE.get()) || state.getValue(StoveBlock.LIT)) {
            return false;
        }
        if (!level.setBlockAndUpdate(cell, state.setValue(StoveBlock.LIT, true))) {
            return false;
        }
        boolean charge = ignition.is(Items.FIRE_CHARGE);
        level.playSound(null, cell, charge ? SoundEvents.FIRECHARGE_USE : SoundEvents.FLINTANDSTEEL_USE,
            SoundSource.BLOCKS, 1.0F, level.random.nextFloat() * 0.4F + 0.8F);
        if (charge) {
            ignition.shrink(1);
        } else {
            ignition.hurtAndBreak(1, level, user, ignored -> {});
        }
        return true;
    }
}
