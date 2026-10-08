package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.terms.Loaded;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.front.api.ZoneView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

final class Field {

    static final int GRAIN_RADIUS = 2;

    static final int GRAIN_UNITS = 6;

    static final int WIDE_HANDS_STEP = 3;

    private static final Comparator<BlockPos> SCAN_ORDER =
        Comparator.comparingInt((BlockPos cell) -> cell.getY())
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ);

    private final Realm realm;
    private final FieldChores harvesting;
    private final FieldChores tilling;
    private final FieldChores sowing;

    private Crop crop;

    private final List<Job> harvest = new ArrayList<>();
    private final List<Job> till = new ArrayList<>();
    private final List<Job> plant = new ArrayList<>();
    private long sweep = Long.MIN_VALUE;

    Field(Crop crop, WorldPos anchor, Runnable changed, PastDay made) {
        this.crop = crop;
        this.realm = anchor.realm();
        this.harvesting = new FieldChores("harvest", Field::cutOrFell, changed, made);
        this.tilling = new FieldChores("till", TillNode::new, changed, made);
        this.sowing = new FieldChores("sow", PlantNode::new, changed, made);
    }

    List<FieldChores> chores() {
        return List.of(harvesting, tilling, sowing);
    }

    void scan(ServerLevel level, ZoneView zone, Crop parsed, long stamp) {
        begin(stamp);
        crop = parsed;
        Set<BlockPos> sown = new LinkedHashSet<>();
        Set<BlockPos> claimed = new LinkedHashSet<>();
        List<BlockPos> ground = new ArrayList<>(zone.cells());
        ground.sort(SCAN_ORDER);
        for (BlockPos cell : ground) {
            if (!Loaded.around(level, cell, crop.reach())) {
                continue;
            }
            BlockState state = level.getBlockState(cell);
            List<BlockPos> ripe = unclaimed(crop.harvestFrom(level, cell), claimed);
            boolean hoe = crop.needsFarmland()
                && Tillage.tilled(level, cell, state, Tillage.PLAIN_HOE).isPresent();
            boolean seed = sowable(level, cell, state, sown);
            if (ripe.isEmpty() && !hoe && !seed) {
                continue;
            }
            Optional<Stances> footings =
                Stances.of(level, Reach.footingsAround(level, cell.above(), 1, 0));
            if (footings.isEmpty()) {
                continue;
            }
            WorldPos at = WorldPos.of(level, cell);
            if (!ripe.isEmpty()) {
                claimed.addAll(ripe);
                List<BlockPos> canopy = new ArrayList<>(crop.canopyOf(level, ripe));
                canopy.removeIf(leaf -> !claimed.add(leaf));
                harvest.add(new Job(at, ripe, canopy, footings.get(), cell));
            }
            if (hoe) {
                till.add(new Job(at, List.of(cell), footings.get(), cell));
            }
            if (seed) {
                plant.add(new Job(at, List.of(cell), footings.get(), cell));
                sown.add(cell.above());
            }
        }
    }

    void settle(long stamp, int wideHands) {
        begin(stamp);
        Crop growing = crop;
        int most = GRAIN_UNITS + WIDE_HANDS_STEP * wideHands;
        boolean fells = growing.habit() instanceof Habit.Tree;
        harvesting.settle(cut(harvest, fells ? 1 : most, growing));
        tilling.settle(cut(till, most, growing));
        sowing.settle(cut(plant, most, growing));
    }

    private void begin(long stamp) {
        if (sweep == stamp) {
            return;
        }
        sweep = stamp;
        harvest.clear();
        till.clear();
        plant.clear();
    }

    private static List<Batch> cut(List<Job> found, int most, Crop growing) {
        Map<BlockPos, Job> free = new LinkedHashMap<>(found.size());
        for (Job job : found) {
            free.put(job.cell(), job);
        }
        List<Batch> batches = new ArrayList<>();
        for (Job seed : found) {
            if (free.remove(seed.cell()) == null) {
                continue;
            }
            List<Job> batch = new ArrayList<>();
            batch.add(seed);
            if (most > 1) {
                BlockPos anchor = seed.cell();
                for (BlockPos around : BlockPos.betweenClosed(
                        anchor.offset(-GRAIN_RADIUS, -GRAIN_RADIUS, -GRAIN_RADIUS),
                        anchor.offset(GRAIN_RADIUS, GRAIN_RADIUS, GRAIN_RADIUS))) {
                    if (batch.size() >= most) {
                        break;
                    }
                    Job near = free.remove(around);
                    if (near != null) {
                        batch.add(near);
                    }
                }
            }
            batches.add(new Batch(new WorkSite.AtBlock(seed.ground()), seed.footings(), growing, batch));
        }
        return List.copyOf(batches);
    }

    private static List<BlockPos> unclaimed(List<BlockPos> cells, Set<BlockPos> claimed) {
        for (BlockPos cell : cells) {
            if (claimed.contains(cell)) {
                return List.of();
            }
        }
        return cells;
    }

    private boolean sowable(ServerLevel level, BlockPos ground, BlockState state, Set<BlockPos> sown) {
        if (crop.needsFarmland() && !(state.getBlock() instanceof FarmBlock)) {
            return false;
        }
        return crop.sowableAt(level, ground, sown);
    }

    private static Node cutOrFell(FarmNode.Chore chore) {
        return chore.batch().crop().habit() instanceof Habit.Tree
            ? new FellNode(chore)
            : new HarvestNode(chore);
    }
}
