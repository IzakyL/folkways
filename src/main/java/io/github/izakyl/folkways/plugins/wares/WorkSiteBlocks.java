package io.github.izakyl.folkways.plugins.wares;

import java.util.List;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class WorkSiteBlocks {

    public static final List<Block> WITHOUT_VANILLA_BLOCK_ENTITY = List.of(
        Blocks.CRAFTING_TABLE,
        Blocks.SMITHING_TABLE,
        Blocks.STONECUTTER
    );

    private WorkSiteBlocks() {
    }

    public static boolean needsMarkerBlockEntity(BlockState state) {
        for (Block block : WITHOUT_VANILLA_BLOCK_ENTITY) {
            if (state.is(block)) {
                return true;
            }
        }
        return false;
    }
}
