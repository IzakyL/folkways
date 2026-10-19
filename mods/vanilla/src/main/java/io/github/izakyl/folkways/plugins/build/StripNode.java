package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class StripNode extends BuildNode {

    StripNode(BuildJob job, Ends ends) {
        super(job, ends);
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        Map<BlockPos, BlockState> before = remaining(level, job().before(), true);
        if (before.isEmpty()) {
            return Outcome.done();
        }
        BlockPos at = before.keySet().iterator().next();
        BlockState standing = before.get(at);
        if (standing.liquid()) {
            level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
            return Outcome.done();
        }
        List<WorkNoise> heard = new ArrayList<>(before.size());
        for (Map.Entry<BlockPos, BlockState> cell : before.entrySet()) {
            if (cell.getValue().getDestroySpeed(level, cell.getKey()) < 0.0F) {
                return refuse(BuildRefusal.NO_WAY_TO_BUILD);
            }
            if (!cell.getValue().isAir()) {
                heard.add(WorkNoise.broke(cell.getKey(), cell.getValue()));
            }
        }
        ItemStack tool = who.held(new ToolNeed.SuitableFor(standing));
        List<ItemStack> spoil = new ArrayList<>(Rubble.clear(level, who.body(), before, remaining(level, job().after(), false), tool));
        before.forEach((pos, stone) -> {
            if (level.getBlockState(pos).isAir()) {
                spoil.addAll(BuildingPerks.discoveries(level, who, pos, stone));
                BuildingPerks.mined(who, stone);
            }
        });
        return new Outcome.Done(List.copyOf(heard), spoil, Optional.of(new Xp(BuildContent.trade(), 1)));
    }
}
