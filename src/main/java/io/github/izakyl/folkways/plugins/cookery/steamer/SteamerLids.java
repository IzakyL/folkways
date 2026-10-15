package io.github.izakyl.folkways.plugins.cookery.steamer;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.SteamerBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Mirrors the native empty-hand secondary-use lid toggle without inventing a fake player. */
final class SteamerLids {
    record Cap(BlockPos at, BlockState original, BlockState expected) { }
    private SteamerLids() { }
    static boolean set(ServerLevel level, BlockPos at, boolean closed) {
        BlockState state = level.getBlockState(at);
        if (!state.is(ModBlocks.STEAMER.get())) { return false; }
        boolean current = state.getValue(SteamerBlock.HAS_LID);
        if (current == closed) { return true; }
        if (!current && level.getBlockState(at.above()).is(state.getBlock())) { return false; }
        return level.setBlock(at, state.setValue(SteamerBlock.HAS_LID, closed), Block.UPDATE_ALL);
    }
    static Cap close(ServerLevel level, BlockPos at) {
        BlockState original = level.getBlockState(at);
        if (!set(level, at, true)) { throw new IllegalStateException("the native top lid cannot close"); }
        return new Cap(at.immutable(), original, level.getBlockState(at));
    }
    static void restore(ServerLevel level, Cap cap) {
        if (level.getBlockState(cap.at()).equals(cap.expected())) {
            set(level, cap.at(), cap.original().getValue(SteamerBlock.HAS_LID));
        }
    }
}
