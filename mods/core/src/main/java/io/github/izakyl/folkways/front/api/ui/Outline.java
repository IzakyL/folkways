package io.github.izakyl.folkways.front.api.ui;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

// A marked block that is one half of a larger thing is outlined with its other half.
@FunctionalInterface
public interface Outline {

    Optional<BlockPos> partner(BlockGetter level, BlockPos pos, BlockState state);
}
