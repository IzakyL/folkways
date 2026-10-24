package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.core.api.structure.StructurePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record Ghost(BlockPos pos, BlockState state, StructurePayload payload) {

    public Ghost(BlockPos pos, BlockState state) {
        this(pos, state, StructurePayload.EMPTY);
    }

    public Ghost {
        pos = pos.immutable();
    }
}
