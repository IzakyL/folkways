package io.github.izakyl.folkways.plugins.wares.mixin;

import io.github.izakyl.folkways.plugins.wares.WorkSiteBlockEntity;
import io.github.izakyl.folkways.plugins.wares.WorkSiteBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(StonecutterBlock.class)
public abstract class StonecutterBlockMixin implements EntityBlock {

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return WorkSiteBlocks.needsMarkerBlockEntity(state) ? new WorkSiteBlockEntity(pos, state) : null;
    }
}
