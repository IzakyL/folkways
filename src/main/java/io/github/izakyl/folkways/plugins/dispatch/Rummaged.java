package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.core.api.work.WorkNoise;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

// A container gone through sounds the way it opens for a player; one with no such voice stays quiet.
final class Rummaged {

    private Rummaged() {
    }

    static WorkNoise at(BlockPos container) {
        BlockPos cell = container.immutable();
        return (level, emitter) -> {
            SoundEvent opened = openSoundOf(level.getBlockState(cell));
            if (opened != null) {
                level.playSound(null, cell, opened, SoundSource.BLOCKS,
                    0.5F, level.random.nextFloat() * 0.1F + 0.9F);
            }
        };
    }

    private static SoundEvent openSoundOf(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof ChestBlock || block instanceof EnderChestBlock) {
            return SoundEvents.CHEST_OPEN;
        }
        if (block instanceof BarrelBlock) {
            return SoundEvents.BARREL_OPEN;
        }
        if (block instanceof ShulkerBoxBlock) {
            return SoundEvents.SHULKER_BOX_OPEN;
        }
        return null;
    }
}
