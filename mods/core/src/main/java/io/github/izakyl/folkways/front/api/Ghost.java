package io.github.izakyl.folkways.front.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record Ghost(BlockPos pos, BlockState state) {

    public Ghost {
        pos = pos.immutable();
    }
}
