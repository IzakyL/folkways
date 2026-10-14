package io.github.izakyl.folkways.plugins.farming;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;

final class Tillage {

    static final ItemStack PLAIN_HOE = new ItemStack(Items.WOODEN_HOE);

    private Tillage() {
    }

    static boolean canTill(Level level, BlockPos ground, BlockState state, ItemStack hoe) {
        if (tilled(level, ground, state, hoe).isPresent()) {
            return true;
        }
        // The hoe's own check needs air above the soil. Recognise grass without changing the world during a scan;
        // the work clears it and asks the hoe again before turning the soil.
        return grass(level.getBlockState(ground.above())) && hoe.canPerformAction(ItemAbilities.HOE_TILL)
            && (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.DIRT_PATH));
    }

    static boolean grass(BlockState state) {
        return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
            || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN);
    }

    static Optional<BlockState> tilled(Level level, BlockPos ground, BlockState state, ItemStack hoe) {
        if (state.getBlock() instanceof FarmBlock || !level.getBlockState(ground.above()).isAir()) {
            return Optional.empty();
        }
        Vec3 hit = Vec3.atCenterOf(ground);
        UseOnContext context = new UseOnContext(level, null, InteractionHand.MAIN_HAND, hoe,
            new BlockHitResult(hit, Direction.UP, ground, false));
        BlockState turned = state.getToolModifiedState(context, ItemAbilities.HOE_TILL, true);
        return turned != null && turned.is(Blocks.FARMLAND) ? Optional.of(turned) : Optional.empty();
    }
}
