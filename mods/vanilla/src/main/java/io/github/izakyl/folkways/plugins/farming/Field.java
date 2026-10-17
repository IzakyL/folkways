package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Loaded;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.front.api.ZoneView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    // How far a body stands from the cell it works: as far as its hands reach, a level up or down.
    private static final int REACH = (int) Math.floor(Reach.PLAYER_BLOCK_REACH);

    private static final Comparator<BlockPos> SCAN_ORDER =
        Comparator.comparingInt((BlockPos cell) -> cell.getY())
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ);

    private final Realm realm;
    private final FieldChores harvesting;
    private final FieldChores tilling;
    private final FieldChores sowing;

    private final Tillage.Clearing clearing;

    private Crop crop;

    private final List<Job> harvest = new ArrayList<>();
    private final List<Job> till = new ArrayList<>();
    private final List<Job> plant = new ArrayList<>();
    private long sweep = Long.MIN_VALUE;

    Field(Crop crop, Tillage.Clearing clearing, WorldPos anchor, Runnable changed, PastDay made) {
        this.crop = crop;
        this.clearing = clearing;
        this.realm = anchor.realm();
        this.harvesting = new FieldChores("harvest", Field::cutOrFell, changed, made);
        this.tilling = new FieldChores("till", chore -> new TillNode(chore, clearing), changed, made);
        this.sowing = new FieldChores("sow", PlantNode::new, changed, made);
    }

    // What one zone's scan found wanting: stands ripe to cut, ground to till, and stands to sow.
    record Tally(int ripe, int till, int sow) {
    }

    List<FieldChores> chores() {
        return List.of(harvesting, tilling, sowing);
    }

    Tally scan(ServerLevel level, ZoneView zone, Crop parsed, long stamp) {
        begin(stamp);
        crop = parsed;
        int ripeBefore = harvest.size();
        int tillBefore = till.size();
        int sowBefore = plant.size();
        Set<BlockPos> sown = new LinkedHashSet<>();
        Set<BlockPos> claimed = new LinkedHashSet<>();
        List<BlockPos> ground = new ArrayList<>(zone.cells());
        ground.sort(SCAN_ORDER);
        Map<BlockPos, Boolean> footed = new HashMap<>();
        Plot plot = Plot.of(zone.cells());
        for (BlockPos cell : ground) {
            if (!Loaded.around(level, cell, crop.reach())) {
                continue;
            }
            BlockState state = level.getBlockState(cell);
            List<BlockPos> ripe = unclaimed(crop.harvestFrom(level, cell), claimed);
            boolean hoe = crop.needsFarmland()
                && Tillage.canTill(level, cell, state, Tillage.PLAIN_HOE, clearing);
            stake(level, plot, cell, sown, footed).ifPresent(plant::add);
            if (ripe.isEmpty() && !hoe) {
                continue;
            }
            Optional<Stances> footings = Stances.of(level, footings(level, cell.above(), footed));
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
        }
        return new Tally(harvest.size() - ripeBefore, till.size() - tillBefore, plant.size() - sowBefore);
    }

    void settle(long stamp) {
        begin(stamp);
        harvesting.settle(crop, harvest);
        tilling.settle(crop, till);
        sowing.settle(crop, plant);
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

    // Every footing in reach of the cell, so the work can be done from wherever the way there is easiest. Whether a
    // body stands somewhere is looked at once a sweep, for all the cells around it.
    private static Set<BlockPos> footings(ServerLevel level, BlockPos target, Map<BlockPos, Boolean> footed) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -REACH; dz <= REACH; dz++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (Reach.withinReach(feet, target) && footed.computeIfAbsent(feet,
                            at -> level.isLoaded(at) && Footing.withFeetAt(level, at))) {
                        cells.add(feet);
                    }
                }
            }
        }
        return cells;
    }

    private static List<BlockPos> unclaimed(List<BlockPos> cells, Set<BlockPos> claimed) {
        for (BlockPos cell : cells) {
            if (claimed.contains(cell)) {
                return List.of();
            }
        }
        return cells;
    }

    // A stand is sown from its first cell, whole or not at all and as one piece of work: every cell of it in the
    // plot, room for all of it, and somewhere to stand in reach of each cell still bare. A crop sown a cell at a time
    // is a stand of one.
    private Optional<Job> stake(ServerLevel level, Plot plot, BlockPos cell, Set<BlockPos> sown,
            Map<BlockPos, Boolean> footed) {
        List<BlockPos> stand = crop.standAt(cell);
        if (!plot.containsAll(stand) || !crop.sowsAt(level, plot, cell)) {
            return Optional.empty();
        }
        for (BlockPos ground : stand) {
            if (!level.isLoaded(ground)
                || crop.needsFarmland() && !(level.getBlockState(ground).getBlock() instanceof FarmBlock)) {
                return Optional.empty();
            }
        }
        if (!crop.sowableAt(level, stand, sown)) {
            return Optional.empty();
        }
        List<BlockPos> bare = new ArrayList<>(stand.size());
        Set<BlockPos> reached = null;
        for (BlockPos ground : stand) {
            if (!crop.bare(level, ground.above())) {
                continue;
            }
            bare.add(ground);
            Set<BlockPos> from = footings(level, ground.above(), footed);
            if (reached == null) {
                reached = from;
            } else {
                reached.retainAll(from);
            }
        }
        // Nobody sows a stalk in the way into the cell they stand in.
        if (reached != null) {
            for (BlockPos ground : bare) {
                if (crop.inTheWay(level, ground.above())) {
                    reached.remove(ground.above());
                }
            }
        }
        Optional<Stances> footings = Stances.of(level, reached);
        if (footings.isEmpty()) {
            return Optional.empty();
        }
        for (BlockPos ground : stand) {
            sown.add(ground.above());
        }
        return Optional.of(new Job(WorldPos.of(level, cell), bare, footings.get(), cell));
    }

    private static Node cutOrFell(FarmNode.Chore chore) {
        return chore.crop().habit() instanceof Habit.Tree
            ? new FellNode(chore)
            : new HarvestNode(chore);
    }
}
