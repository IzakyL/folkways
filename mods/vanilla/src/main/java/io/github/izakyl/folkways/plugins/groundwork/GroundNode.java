package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

abstract class GroundNode implements Node {

    private final NodeSpec spec;
    private final Chores.Chore chore;

    GroundNode(NodeSpec spec, Chores.Chore chore) {
        this.spec = spec;
        this.chore = chore;
    }

    static NodeSpec.Builder declare(Chores.Chore chore, Vocation by, Workload workload) {
        Job job = chore.job();
        return NodeSpec.of(chore.id(), GroundworkContent.ID, job.site(), job.footings(), workload)
            .vocation(by)
            .gesture(WorkGesture.SWING)
            .focus(job.cell());
    }

    @Override
    public final NodeSpec spec() {
        return spec;
    }

    @Override
    public final Optional<RefusalKind> planned(ServerLevel level) {
        chore.tending().planned(chore);
        return Optional.empty();
    }

    @Override
    public final void ended(ServerLevel level, Ending how) {
        chore.tending().ended(chore, how);
    }

    @Override
    public boolean ready(ServerLevel level) {
        return level.isLoaded(chore.job().cell());
    }

    @Override
    public final Outcome commit(ServerLevel level, Worker who) {
        Outcome done = work(level, who, chore.job());
        chore.tending().made().record(spec, done, level.getGameTime());
        return done;
    }

    abstract Outcome work(ServerLevel level, Worker who, Job job);

    final Outcome refuse(GroundRefusal why) {
        return Outcome.failed(why);
    }

    // Breaks the block as a hand with this tool would, and hands back what it drops.
    static List<ItemStack> take(ServerLevel level, LivingEntity body, BlockPos cell, BlockState state, ItemStack tool) {
        List<ItemStack> drops = Block.getDrops(state, level, cell, level.getBlockEntity(cell), body, tool);
        if (!level.removeBlock(cell, false)) {
            return List.of();
        }
        state.spawnAfterBreak(level, cell, tool, true);
        return drops;
    }

    // A reciprocal pair must both be gone before shape updates notice either half missing. Its loot is read
    // while both halves still exist, and returned once, without spawning and finding ground items.
    static List<ItemStack> take(ServerLevel level, LivingEntity body, List<BlockPos> cells, ItemStack tool) {
        if (cells.size() == 1) {
            BlockPos cell = cells.getFirst();
            return take(level, body, cell, level.getBlockState(cell), tool);
        }
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        List<ItemStack> drops = new ArrayList<>();
        for (BlockPos cell : cells) {
            BlockState state = level.getBlockState(cell);
            if (!state.isAir()) {
                before.put(cell, state);
                drops.addAll(Block.getDrops(state, level, cell, level.getBlockEntity(cell), body, tool));
            }
        }
        before.forEach((cell, state) -> level.setBlock(cell, state.getFluidState().createLegacyBlock(),
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
        before.forEach((cell, state) -> {
            level.updateNeighborsAt(cell, state.getBlock());
            state.updateIndirectNeighbourShapes(level, cell, Block.UPDATE_ALL);
            BlockState left = level.getBlockState(cell);
            left.updateNeighbourShapes(level, cell, Block.UPDATE_ALL);
            left.updateIndirectNeighbourShapes(level, cell, Block.UPDATE_ALL);
            state.spawnAfterBreak(level, cell, tool, true);
        });
        return drops;
    }
}
