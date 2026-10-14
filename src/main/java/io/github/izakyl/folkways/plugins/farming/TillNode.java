package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ItemAbilities;

final class TillNode extends FarmNode {

    private static final ToolUse HOE = ToolUse.of(new ToolNeed.Ability(ItemAbilities.HOE_TILL));

    TillNode(Chore chore) {
        super(FarmNode.declare(chore, Workload.Once.of(FarmingContent.CELL_TICKS,
            FarmingContent::haste)).tools(HOE).doing(FarmingContent.TILLING).done(), chore);
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job job) {
        BlockPos ground = job.cell();
        if (!level.isLoaded(ground)) {
            return refuse(FarmRefusal.NOTHING_TO_WORK);
        }
        ItemStack hoe = who.held(HOE.need());
        BlockState state = level.getBlockState(ground);
        if (!Tillage.canTill(level, ground, state, hoe)) {
            return Outcome.done();
        }
        List<WorkNoise> heard = new ArrayList<>();
        List<ItemStack> taken = new ArrayList<>();
        BlockPos above = ground.above();
        BlockState grass = level.getBlockState(above);
        if (Tillage.grass(grass)) {
            taken.addAll(Yield.take(level, who.body(), above, grass));
            heard.add(WorkNoise.broke(above, grass));
        }
        Optional<BlockState> turned = Tillage.tilled(level, ground, state, hoe);
        if (turned.isEmpty()) {
            return new Outcome.Done(heard, taken, Optional.empty());
        }
        level.setBlock(ground, turned.get(), 3);
        heard.add(WorkNoise.placed(ground, turned.get()));
        return new Outcome.Done(heard, taken,
            Optional.of(new Xp(FarmingContent.trade(), 1)));
    }
}
