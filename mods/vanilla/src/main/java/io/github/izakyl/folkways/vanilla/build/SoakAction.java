package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.WorkNoise;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;

/** Lets a bucket of water into a block laid dry where the target wants it waterlogged, as a player would. */
final class SoakAction extends BuildAction {

    SoakAction(BuildJob job) {
        super(job);
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        BlockPos at = cell(level);
        BlockState wet = job().goal();
        BlockState standing = level.getBlockState(at);
        // The block it waits on is laid first; anything else standing there is not for the bucket to change.
        if (!standing.is(wet.getBlock()) || !standing.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return refuse(BuildRefusal.CONTESTED);
        }
        spend(who);
        level.setBlock(at, standing.setValue(BlockStateProperties.WATERLOGGED, true), Block.UPDATE_ALL);
        level.scheduleTick(at, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return new Outcome.Done(List.of(WorkNoise.placed(at, wet)), List.of(new ItemStack(Items.BUCKET)),
            Optional.empty());
    }
}
