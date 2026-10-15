package io.github.izakyl.folkways.plugins.cookery.stockpot;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

record StockpotBefore(BlockPos at, BlockState state, CompoundTag contents) {

    static StockpotBefore of(ServerLevel level, StockpotBlockEntity pot) {
        // Force even an empty persistent-data compound into the snapshot, so a new claim is undone on failure.
        pot.getPersistentData();
        return new StockpotBefore(pot.getBlockPos(), pot.getBlockState(),
            pot.saveWithFullMetadata(level.registryAccess()));
    }

    void restore(ServerLevel level) {
        level.setBlockAndUpdate(at, state);
        if (level.getBlockEntity(at) instanceof StockpotBlockEntity pot) {
            pot.loadWithComponents(contents.copy(), level.registryAccess());
            pot.setChanged();
            level.sendBlockUpdated(at, state, state, 3);
        }
    }
}
