package io.github.izakyl.folkways.chiselsandbits.mixin;

import io.github.izakyl.folkways.chiselsandbits.SchematicStatistics;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(targets = "mod.chiselsandbits.block.entities.ChiseledBlockEntity$MutableStatistics", remap = false)
abstract class MutableStatisticsMixin implements SchematicStatistics {
    @Unique private boolean folkways$reading;

    @Shadow
    protected abstract void recalculate(LevelAccessor level, StateEntryStorage source, BlockPos pos, boolean updateWorld);

    @Override
    public void folkways$recalculate(LevelAccessor level, StateEntryStorage storage, BlockPos pos) {
        folkways$reading = true;
        try {
            recalculate(level, storage, pos, true);
        } finally {
            folkways$reading = false;
        }
    }

    @ModifyVariable(method = "updatePrimaryState", at = @At("HEAD"), argsOnly = true)
    private boolean folkways$onlyReadStatistics(boolean updateWorld) {
        return updateWorld && !folkways$reading;
    }
}
