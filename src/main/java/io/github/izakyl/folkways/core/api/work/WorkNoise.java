package io.github.izakyl.folkways.core.api.work;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

// What a piece of work sounds like, played once the work is done. Core knows only the world's own
// noises - a block broken or placed, an item picked up; anything else is the plugin's to voice.
@FunctionalInterface
public interface WorkNoise {

    void sound(ServerLevel level, Entity emitter);

    static WorkNoise broke(BlockPos at, BlockState state) {
        BlockPos cell = at.immutable();
        return (level, emitter) -> level.levelEvent(2001, cell, Block.getId(state));
    }

    static WorkNoise placed(BlockPos at, BlockState state) {
        BlockPos cell = at.immutable();
        return (level, emitter) -> {
            SoundType sound = state.getSoundType(level, cell, emitter);
            level.playSound(null, cell, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        };
    }

    static WorkNoise stowed() {
        return (level, emitter) -> level.playSound(null, emitter.getX(), emitter.getY(), emitter.getZ(),
            SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS,
            0.2F, (level.random.nextFloat() - level.random.nextFloat()) * 1.4F + 2.0F);
    }

    // A plain voice, from the working body.
    static WorkNoise voice(SoundEvent sound) {
        return (level, emitter) -> level.playSound(null, emitter.blockPosition(), sound, SoundSource.BLOCKS,
            1.0F, 1.0F);
    }

    // A plain voice, from the named place.
    static WorkNoise voice(SoundEvent sound, BlockPos at) {
        BlockPos cell = at.immutable();
        return (level, emitter) -> level.playSound(null, cell, sound, SoundSource.BLOCKS, 1.0F, 1.0F);
    }
}
