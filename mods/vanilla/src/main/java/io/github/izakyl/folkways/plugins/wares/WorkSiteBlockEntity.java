package io.github.izakyl.folkways.plugins.wares;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class WorkSiteBlockEntity extends BlockEntity {

    public WorkSiteBlockEntity(BlockPos pos, BlockState state) {
        super(WaresBlockEntities.WORK_SITE.get(), pos, state);
    }
}
