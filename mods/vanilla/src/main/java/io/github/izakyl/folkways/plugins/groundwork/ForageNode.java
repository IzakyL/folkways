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
import io.github.izakyl.folkways.plugins.farming.CropBehaviors;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class ForageNode extends GroundNode {
    private final Block plant;
    private final Predicate<BlockPos> spared;

    ForageNode(ServerLevel level, Chores.Chore chore, Predicate<BlockPos> spared) {
        super(declare(level, chore), chore);
        this.plant = level.getBlockState(chore.job().cell()).getBlock();
        this.spared = spared;
    }

    private static NodeSpec declare(ServerLevel level, Chores.Chore chore) {
        BlockState state = level.getBlockState(chore.job().cell());
        var spec = GroundNode.declare(chore, FarmingContent.trade(), Workload.Once.of(
            GroundworkContent.CELL_TICKS, FarmingContent::haste))
            .leaves(Leaves.room(3))
            .doing(GroundworkContent.FORAGING, state.getBlock());
        if (!Foraging.renewable(state) && state.requiresCorrectToolForDrops()) {
            spec.tools(new ToolUse(new ToolNeed.SuitableFor(state), 1));
        }
        return spec.done();
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job job) {
        BlockPos cell = job.cell();
        BlockState state = level.getBlockState(cell);
        if (!state.is(plant) || !Foraging.ripe(level, cell)) return Outcome.done();
        List<BlockPos> cells = Foraging.cells(level, cell);
        if (cells.isEmpty() || !job.cells().equals(cells) || cells.stream().anyMatch(at -> !level.isLoaded(at)
                || spared.test(at) || level.getBlockState(at).getDestroySpeed(level, at) < 0.0F)) {
            return refuse(GroundRefusal.NOTHING_TO_WORK);
        }
        if (Foraging.renewable(state) && !state.is(Blocks.SWEET_BERRY_BUSH)
                && !state.is(Blocks.CAVE_VINES) && !state.is(Blocks.CAVE_VINES_PLANT)) {
            return CropBehaviors.of(plant).flatMap(crop -> crop.gather(level, who, List.of(cell)))
                .<Outcome>map(harvest -> new Outcome.Done(harvest.noises(), harvest.items(),
                    harvest.worked() > 0 ? Optional.of(new Xp(FarmingContent.trade(), 1)) : Optional.empty()))
                .orElseGet(Outcome::done);
        }
        List<ItemStack> items;
        List<AABB> bounds = new ArrayList<>(List.of(new AABB(cell).inflate(1)));
        WorldSpaces.world(level, WorldPos.of(level, cell))
            .ifPresent(world -> bounds.add(AABB.ofSize(world, 3, 3, 3)));
        try {
            var captured = ItemDrops.capture(level, bounds, () -> {
                if (state.is(Blocks.SWEET_BERRY_BUSH)) {
                    // This vanilla interaction only uses Player as a nullable game-event source.
                    state.useWithoutItem(level, null, new BlockHitResult(Vec3.atCenterOf(cell), Direction.UP, cell, false));
                    return List.<ItemStack>of();
                }
                if (Foraging.renewable(state)) {
                    CaveVines.use(who.body(), state, level, cell);
                    return List.<ItemStack>of();
                }
                return take(level, who.body(), cell, state, who.held(new ToolNeed.SuitableFor(state)));
            });
            items = new ArrayList<>(captured.result());
            items.addAll(captured.items());
        } catch (ItemDrops.CaptureFailure broken) {
            // Once the plant changed, settle its captured fruit instead of harvesting it again.
            if (level.getBlockState(cell).equals(state)) throw broken;
            items = broken.items();
            org.slf4j.LoggerFactory.getLogger("folkways-groundwork").warn("forage {} changed before notification failed", cell, broken);
        }
        if (level.getBlockState(cell).equals(state)) return Outcome.done();
        WorkNoise noise = Foraging.renewable(state) ? WorkNoise.placed(cell, level.getBlockState(cell))
            : WorkNoise.broke(cell, state);
        return new Outcome.Done(List.of(noise), items, Optional.of(new Xp(FarmingContent.trade(), 1)));
    }
}
