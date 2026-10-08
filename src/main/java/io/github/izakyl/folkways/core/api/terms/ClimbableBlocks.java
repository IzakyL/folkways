package io.github.izakyl.folkways.core.api.terms;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class ClimbableBlocks {

    private ClimbableBlocks() {
    }

    public static boolean isClimbable(BlockState state) {
        return state.is(BlockTags.CLIMBABLE);
    }

    public static boolean offersFloor(BlockState state) {
        return state.is(Blocks.SCAFFOLDING);
    }
}
