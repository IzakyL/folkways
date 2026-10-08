package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

final class PourNode extends BuildNode {

    PourNode(BuildJob job, Ends ends) {
        super(job, ends);
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        BlockPos at = cell(level);
        BlockState standing = level.getBlockState(at);
        if (!(standing.getBlock() instanceof LiquidBlockContainer container)
            || !container.canPlaceLiquid(null, level, at, standing, Fluids.WATER)) {
            return refuse(BuildRefusal.NO_WAY_TO_BUILD);
        }
        spend(who);
        container.placeLiquid(level, at, standing, Fluids.WATER.getSource(false));
        return new Outcome.Done(List.of(WorkNoise.placed(at, level.getBlockState(at))),
            List.of(new ItemStack(Items.BUCKET)), Optional.of(new Xp(BuildContent.trade(), 1)));
    }
}
