package io.github.izakyl.folkways.plugins.farming;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

final class Yield {

    private Yield() {
    }

    static List<ItemStack> take(ServerLevel level, LivingEntity body, BlockPos cell, BlockState state) {
        List<ItemStack> drops = Block.getDrops(state, level, cell,
            level.getBlockEntity(cell), body, ItemStack.EMPTY);
        if (!level.removeBlock(cell, false)) {
            return List.of();
        }
        state.spawnAfterBreak(level, cell, ItemStack.EMPTY, true);
        return drops;
    }
}
