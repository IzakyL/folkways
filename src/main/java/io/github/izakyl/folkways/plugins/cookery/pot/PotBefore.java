package io.github.izakyl.folkways.plugins.cookery.pot;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

record PotBefore(BlockPos at, BlockState state, CompoundTag contents) {
    static PotBefore of(ServerLevel level, PotBlockEntity pot) {
        pot.getPersistentData();
        return new PotBefore(pot.getBlockPos(), pot.getBlockState(), pot.saveWithFullMetadata(level.registryAccess()));
    }

    void restore(ServerLevel level) {
        level.setBlockAndUpdate(at, state);
        if (level.getBlockEntity(at) instanceof PotBlockEntity pot) {
            pot.loadWithComponents(contents.copy(), level.registryAccess());
            pot.setChanged();
            level.sendBlockUpdated(at, state, state, 3);
        }
    }
}
