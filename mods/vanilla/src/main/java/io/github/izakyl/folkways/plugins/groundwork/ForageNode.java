package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.terms.ItemDrops;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

final class ForageNode extends GroundNode {
    private final Block plant;
    private final Predicate<BlockPos> spared;
    private final Set<BlockPos> marked;

    ForageNode(ServerLevel level, Chores.Chore chore, Predicate<BlockPos> spared, Set<BlockPos> marked) {
        super(declare(level, chore), chore);
        this.plant = level.getBlockState(chore.job().cell()).getBlock();
        this.spared = spared;
        this.marked = Set.copyOf(marked);
    }

    private static NodeSpec declare(ServerLevel level, Chores.Chore chore) {
        BlockState state = level.getBlockState(chore.job().cell());
        int berries = (int) chore.job().cells().stream()
            .filter(cell -> CaveVines.hasGlowBerries(level.getBlockState(cell))).count();
        var spec = GroundNode.declare(chore, FarmingContent.trade(), Workload.Once.of(
            GroundworkContent.CELL_TICKS * (Foraging.vine(state) ? chore.job().cells().size() : 1), FarmingContent::haste))
            .leaves(Foraging.vine(state) ? Leaves.ofStacks(List.of(new ItemStack(Items.GLOW_BERRIES, berries))) : Leaves.room(3))
            .doing(GroundworkContent.FORAGING, state.getBlock());
        if (state.requiresCorrectToolForDrops()) {
            spec.tools(new ToolUse(new ToolNeed.SuitableFor(state), 1));
        }
        return spec.done();
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job job) {
        BlockPos cell = job.cell();
        BlockState state = level.getBlockState(cell);
        boolean vine = Foraging.vine(state);
        if ((!state.is(plant) && !(vine && Foraging.vine(plant.defaultBlockState())))
                || !Foraging.root(state)) return Outcome.done();
        List<BlockPos> cells = Foraging.cells(level, cell, marked);
        if (cells.isEmpty() || !vine && !job.cells().equals(cells) || cells.stream().anyMatch(at -> !level.isLoaded(at)
                || spared.test(at) || level.getBlockState(at).getDestroySpeed(level, at) < 0.0F)) {
            return refuse(GroundRefusal.NOTHING_TO_WORK);
        }
        // Cut vines from the bottom so every marked segment pays its loot before losing its support.
        List<BlockPos> cut = Foraging.vine(state) ? cells : List.of(cell);
        List<BlockState> seen = cut.stream().map(level::getBlockState).toList();
        List<AABB> bounds = new ArrayList<>();
        for (BlockPos at : cells) {
            bounds.add(new AABB(at).inflate(1));
            WorldSpaces.world(level, WorldPos.of(level, at))
                .ifPresent(world -> bounds.add(AABB.ofSize(world, 3, 3, 3)));
        }
        List<ItemStack> items = new ArrayList<>();
        List<WorkNoise> noises = new ArrayList<>();
        var captured = ItemDrops.capture(level, bounds, () -> {
            for (int index = 0; index < cut.size(); index++) {
                BlockPos at = cut.get(index);
                BlockState block = seen.get(index);
                items.addAll(take(level, who.body(), at, block, who.held(new ToolNeed.SuitableFor(block))));
                noises.add(WorkNoise.broke(at, block));
            }
            return true;
        });
        items.addAll(captured.items());
        return new Outcome.Done(List.copyOf(noises), items, Optional.of(new Xp(FarmingContent.trade(), cut.size())));
    }
}
