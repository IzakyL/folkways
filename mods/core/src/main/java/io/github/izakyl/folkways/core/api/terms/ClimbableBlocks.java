package io.github.izakyl.folkways.core.api.terms;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class ClimbableBlocks {

    // Blocks a resident climbs that vanilla does not call climbable: a modded stepladder climbs in steps inside its
    // own column, so the ways and the walk take it as a ladder, while players keep the block's own physics.
    public static final TagKey<Block> RESIDENT_CLIMBABLE = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways", "climbable"));

    private ClimbableBlocks() {
    }

    public static boolean isClimbable(BlockState state) {
        return state.is(BlockTags.CLIMBABLE) || state.is(RESIDENT_CLIMBABLE);
    }

    public static boolean offersFloor(BlockState state) {
        return state.is(Blocks.SCAFFOLDING);
    }
}
