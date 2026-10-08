package io.github.izakyl.folkways.core.api.terms;

import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class ObstructedPathRegion extends PathNavigationRegion {

    private final PathNavigationRegion world;
    private final Map<BlockPos, BlockState> obstructions;

    public ObstructedPathRegion(Level level, BlockPos anchor, PathNavigationRegion world,
            Map<BlockPos, BlockState> obstructions) {
        super(level, anchor, anchor);
        this.world = world;
        this.obstructions = obstructions;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState blocked = obstructions.get(pos);
        return blocked != null ? blocked : world.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        BlockState blocked = obstructions.get(pos);
        return blocked != null ? blocked.getFluidState() : world.getFluidState(pos);
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return world.getBlockEntity(pos);
    }

    @Override
    public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
        return this;
    }
}
