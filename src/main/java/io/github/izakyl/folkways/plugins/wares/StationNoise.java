package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.work.WorkNoise;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

// A station finishing a run sounds the way the vanilla block does when a player works it, from the block.
final class StationNoise {

    private StationNoise() {
    }

    static List<WorkNoise> of(Block station, BlockPos at) {
        SoundEvent sound = station == Blocks.CRAFTING_TABLE ? SoundEvents.CRAFTER_CRAFT
            : station == Blocks.SMITHING_TABLE ? SoundEvents.SMITHING_TABLE_USE
            : station == Blocks.STONECUTTER ? SoundEvents.UI_STONECUTTER_TAKE_RESULT
            : null;
        return sound == null ? List.of() : List.of(WorkNoise.voice(sound, at));
    }
}
