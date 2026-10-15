package io.github.izakyl.folkways.plugins.cookery.teapot;

import com.github.ysbbbbbb.kaleidoscopecookery.block.drink.EmptyCupBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.drink.TeacupBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.item.TeacupItem;
import com.github.ysbbbbbb.kaleidoscopecookery.item.TeapotItem;
import com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Narrow equivalents of the two hard-Player cup interactions, using real placed blocks and pot data. */
final class TeaCups {
    private TeaCups() { }
    static boolean place(ServerLevel level, BlockPos at, ItemStack actual) {
        if (!(actual.getItem() instanceof BlockItem item) || !level.getBlockState(at).isAir()) { return false; }
        var hit = new BlockHitResult(Vec3.atCenterOf(at).add(0, -0.5, 0), Direction.UP, at.below(), false);
        return item.place(new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, actual, hit)).consumesAction();
    }
    static boolean pour(ServerLevel level, BlockPos at, ItemStack pot) {
        var state = level.getBlockState(at);
        ItemStack portion = TeapotItem.getPourOut(pot, level);
        if (!state.is(ModBlocks.EMPTY_CUP.get()) || state.getValue(EmptyCupBlock.CUP_COUNT) != 1
                || !(portion.getItem() instanceof TeacupItem item && item.getBlock() instanceof TeacupBlock cup)) { return false; }
        TeapotItem.pourOut(pot, level);
        level.playSound(null, at, SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 1, 1);
        return level.setBlockAndUpdate(at, cup.defaultBlockState().setValue(cup.getCupCountProperty(), 1)
            .setValue(cup.getTeaCountProperty(), 1).setValue(HorizontalDirectionalBlock.FACING, state.getValue(HorizontalDirectionalBlock.FACING)));
    }
    static boolean take(ServerLevel level, BlockPos at, LivingEntity user) {
        var state = level.getBlockState(at);
        if (!(state.getBlock() instanceof TeacupBlock cup) || state.getValue(cup.getCupCountProperty()) != 1
                || state.getValue(cup.getTeaCountProperty()) != 1) { return false; }
        // This is the same single filled-cup result as the native empty-hand branch, from the actual block.
        var actual = Block.getDrops(state, level, at, null, user, ItemStack.EMPTY);
        if (actual.size() != 1 || actual.getFirst().getCount() != 1 || actual.getFirst().getItem() != cup.asItem()) { return false; }
        if (!level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState())) { return false; }
        actual.forEach(stack -> ItemUtils.getItemToLivingEntity(user, stack));
        level.playSound(null, at, state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1, 1);
        return true;
    }
}
