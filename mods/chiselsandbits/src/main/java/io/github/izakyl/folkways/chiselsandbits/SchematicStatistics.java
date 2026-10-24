package io.github.izakyl.folkways.chiselsandbits;

import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;

public interface SchematicStatistics {
    void folkways$recalculate(LevelAccessor level, StateEntryStorage storage, BlockPos pos);
}
