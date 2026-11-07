package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.ToolUse;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.support.Leaves;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/** An explicitly selected way to harvest only the roots already claimed by one farm job. */
public interface HarvestMethod {
    boolean supports(Block crop);

    List<ToolUse> tools();

    default List<Amount> leaves(int roots) {
        return Leaves.room(roots * 5);
    }

    Outcome harvest(ServerLevel level, Worker who, Block crop, List<BlockPos> roots);
}
