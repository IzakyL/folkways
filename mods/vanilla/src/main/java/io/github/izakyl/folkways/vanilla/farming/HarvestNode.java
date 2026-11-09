package io.github.izakyl.folkways.vanilla.farming;

import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.WorkNoise;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.api.Xp;
import io.github.izakyl.folkways.core.work.support.Leaves;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

final class HarvestNode extends FarmNode {

    private final Crop crop;
    private final FieldChores tending;

    HarvestNode(Chore chore) {
        super(FarmNode.declare(chore, Workload.Once.of(FarmingContent.CELL_TICKS,
            FarmingContent::haste))
            .tools(chore.crop().harvestMethod().map(HarvestMethod::tools).orElseGet(List::of))
            .leaves(leaves(chore))
            .doing(FarmingContent.HARVESTING, chore.crop().fruit()).done(), chore);
        this.crop = chore.crop();
        this.tending = chore.tending();
    }

    // The fruit off each cell, and the seed of a crop cut whole: one apiece, as how many is known only once cut.
    private static List<Amount> leaves(Chore chore) {
        Crop crop = chore.crop();
        int cells = chore.job().cells().size();
        if (crop.harvestMethod().isPresent()) return crop.harvestMethod().get().leaves(cells);
        if (crop.behavior().isPresent()) {
            return crop.behavior().get().leaves(cells);
        }
        List<Amount> left = new ArrayList<>(Leaves.ofBlocks(
            Collections.nCopies(cells, crop.fruit().defaultBlockState())));
        if (crop.cutWhole()) {
            left.add(new Amount(crop.seedSpec(), cells));
        }
        return Leaves.merged(left);
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Job job) {
        if (crop.harvestMethod().isPresent()) {
            return crop.harvestMethod().get().harvest(level, who, crop.block(), job.cells());
        }
        Optional<CropBehavior.Harvest> gathered = crop.behavior().flatMap(adapted ->
            adapted.gather(level, who, job.cells()));
        if (gathered.isPresent()) {
            CropBehavior.Harvest result = gathered.get();
            if (result.worked() == 0) return Outcome.done();
            return new Outcome.Done(result.noises(), result.items(),
                Optional.of(new Xp(FarmingContent.trade(), result.worked())));
        }
        List<ItemStack> taken = new ArrayList<>();
        List<WorkNoise> heard = new ArrayList<>();
        boolean replants = who.effects().has(FarmingContent.REPLANTS);
        boolean cut = false;
        int broken = 0;
        List<BlockPos> cells = tending.neighbors(job, (int) who.effects().value(FarmingContent.CHAIN));
        for (BlockPos cell : currentColumn(level, cells)) {
            if (!level.isLoaded(cell)) {
                continue;
            }
            BlockState state = level.getBlockState(cell);
            if (!crop.cuts(state) || crop.cutWhole() && !crop.ripe(level, cell)) {
                cut = true;
                continue;
            }
            List<ItemStack> drops = Yield.take(level, who.body(), cell, state);
            double bonus = who.effects().value(FarmingContent.HARVEST_BONUS);
            for (ItemStack stack : drops) {
                // Separate seeds are propagation materials; carrots/potatoes remain primary food products.
                if (!stack.is(crop.seed()) || stack.has(net.minecraft.core.component.DataComponents.FOOD)) {
                    double extra = stack.getCount() * bonus;
                    stack.grow((int) extra + (level.random.nextDouble() < extra % 1 ? 1 : 0));
                }
            }
            CropCare.of(level).forget(cell);
            taken.addAll(drops);
            heard.add(WorkNoise.broke(cell, state));
            broken++;
            cut = true;
            if (replants) {
                replant(level, who, cell, taken).ifPresent(heard::add);
            }
        }
        if (!cut) {
            return refuse(FarmRefusal.NOTHING_TO_WORK);
        }
        List<ItemStack> yielded = new ArrayList<>(taken.size());
        for (ItemStack stack : taken) {
            if (!stack.isEmpty()) {
                yielded.add(stack);
            }
        }
        return new Outcome.Done(List.copyOf(heard), List.copyOf(yielded),
            Optional.of(new Xp(FarmingContent.trade(), Math.max(1, broken))));
    }

    // A stalk can grow while the worker comes to it; the new top must come away before anything below it.
    private List<BlockPos> currentColumn(ServerLevel level, List<BlockPos> cells) {
        if (!(crop.habit() instanceof Habit.Cane) || crop.behavior().isPresent()) {
            return cells;
        }
        Set<BlockPos> column = new LinkedHashSet<>(cells);
        for (BlockPos cell : cells) {
            BlockPos above = cell.above();
            while (level.isLoaded(above) && crop.grows(level.getBlockState(above))) {
                if (!column.add(above)) {
                    break;
                }
                above = above.above();
            }
        }
        return column.stream().sorted(Comparator.comparingInt((BlockPos cell) -> cell.getY()).reversed()).toList();
    }

    private Optional<WorkNoise> replant(ServerLevel level, Worker who, BlockPos cell, List<ItemStack> taken) {
        if (!crop.cutWhole()) {
            return Optional.empty();
        }
        List<net.minecraft.world.item.Item> seeds = crop.behavior().map(CropBehavior::seeds)
            .orElseGet(() -> List.of(crop.seed()));
        for (var seed : seeds) {
            for (ItemStack stack : taken) {
                int before = stack.getCount();
                if (!stack.isEmpty() && stack.is(seed) && crop.plant(level, who, cell, stack)) {
                    if (level.random.nextDouble() < who.effects().value(FarmingContent.SEED_SAVING)) {
                        stack.grow(Math.max(0, before - stack.getCount()));
                    }
                    CropCare.of(level).tend(cell, level.getBlockState(cell), who.effects().value(FarmingContent.GROWTH));
                    return Optional.of(WorkNoise.placed(cell, level.getBlockState(cell)));
                }
            }
        }
        return Optional.empty();
    }
}
