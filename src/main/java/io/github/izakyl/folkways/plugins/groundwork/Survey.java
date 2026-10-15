package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import io.github.izakyl.folkways.plugins.farming.Felling;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// What each kind of zone asks for, read off the world one sweep at a time. Only the cells it can be done at now are
// asked for: a box is cleared from the top down and filled from the bottom up, a layer opening the next as it goes.
final class Survey {

    // How far a body stands from the cell it works: as far as its hands reach.
    private static final int REACH = (int) Math.floor(Reach.PLAYER_BLOCK_REACH);

    private static final Comparator<BlockPos> DOWNWARD =
        Comparator.comparingInt((BlockPos cell) -> -cell.getY())
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ);

    private static final Comparator<BlockPos> UPWARD =
        Comparator.comparingInt((BlockPos cell) -> cell.getY())
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ);

    private final ServerLevel level;
    private final Set<BlockPos> cells;
    private final Map<BlockPos, Boolean> footed = new HashMap<>();

    Survey(ServerLevel level, Set<BlockPos> cells) {
        this.level = level;
        this.cells = cells;
    }

    List<Job> clear(Predicate<BlockPos> member) {
        return clear(member, state -> false, Set.of());
    }

    // Every block in the box goes, but a colony's own blocks, liquids, what no hand breaks and what the zone keeps.
    // A cell waits for the one over it, and nobody digs out the block they stand on. A cell other work of the zone
    // has taken is left to it, and what lies under it waits for that work too.
    List<Job> clear(Predicate<BlockPos> member, Predicate<BlockState> kept, Set<BlockPos> claimed) {
        Predicate<BlockPos> wanted = toClear(member, kept);
        List<Job> jobs = new ArrayList<>();
        for (BlockPos cell : sorted(DOWNWARD)) {
            if (!wanted.test(cell) || claimed.contains(cell)
                || cells.contains(cell.above()) && wanted.test(cell.above())) {
                continue;
            }
            stances(cell, Set.of(cell.above())).ifPresent(where -> jobs.add(Job.at(cell, site(cell), where)));
        }
        return jobs;
    }

    List<Job> fill() {
        return fill(Set.of());
    }

    // Every open cell is laid, the lowest first; nobody lays a block where they stand or where their head is. A cell
    // other work of the zone has taken, a weed to pull, is laid once that is done.
    List<Job> fill(Set<BlockPos> claimed) {
        Predicate<BlockPos> wanted = cell -> level.isLoaded(cell) && open(level.getBlockState(cell));
        List<Job> jobs = new ArrayList<>();
        for (BlockPos cell : sorted(UPWARD)) {
            if (!wanted.test(cell) || claimed.contains(cell)
                || cells.contains(cell.below()) && wanted.test(cell.below())) {
                continue;
            }
            stances(cell, Set.of(cell, cell.below())).ifPresent(where -> jobs.add(Job.at(cell, site(cell), where)));
        }
        return jobs;
    }

    List<Job> weed() {
        List<Job> jobs = new ArrayList<>();
        for (BlockPos cell : sorted(UPWARD)) {
            if (level.isLoaded(cell) && weedAt(level.getBlockState(cell))) {
                stances(cell, Set.of()).ifPresent(where -> jobs.add(Job.at(cell, site(cell), where)));
            }
        }
        return jobs;
    }

    // A tree is felled from the foot of its trunk in the box, whole, the way a wood lot fells one. A log with no
    // leaves of its own is no tree, but a beam or a wall, and stays.
    List<Job> fell() {
        List<Job> jobs = new ArrayList<>();
        Set<BlockPos> claimed = new LinkedHashSet<>();
        for (BlockPos cell : sorted(UPWARD)) {
            if (claimed.contains(cell) || !level.isLoaded(cell) || !level.getBlockState(cell).is(BlockTags.LOGS)
                || level.getBlockState(cell.below()).is(BlockTags.LOGS)) {
                continue;
            }
            List<BlockPos> tree = Felling.tree(level, cell);
            if (tree.isEmpty() || tree.stream().anyMatch(claimed::contains)) {
                continue;
            }
            List<BlockPos> canopy = Felling.canopy(level, tree);
            if (canopy.isEmpty()) {
                continue;
            }
            Optional<Stances> where = stances(cell, Set.of());
            if (where.isEmpty()) {
                continue;
            }
            claimed.addAll(tree);
            jobs.add(new Job(Job.keyOf(cell), site(cell), cell, tree, canopy, where.get()));
        }
        return jobs;
    }

    // A stack lies in the box when the cell it lies in is one of the box's, wherever the box stands: aboard a ship,
    // a stack lies in the world over the deck, never among the cells the ship is kept in.
    List<Job> haul(Function<BlockPos, WorldPos> address) {
        Set<WorldPos> held = new HashSet<>();
        AABB box = null;
        for (BlockPos cell : cells) {
            WorldPos at = address.apply(cell);
            held.add(at);
            Optional<Vec3> shown = WorldSpaces.world(level, at);
            if (shown.isPresent()) {
                AABB one = new AABB(shown.get(), shown.get());
                box = box == null ? one : box.minmax(one);
            }
        }
        if (box == null) {
            return List.of();
        }
        List<ItemEntity> lying = level.getEntitiesOfClass(ItemEntity.class, box.inflate(1.5D),
            drop -> drop.isAlive() && !drop.getItem().isEmpty() && held.contains(WorldSpaces.at(drop)));
        lying.sort(Comparator.comparing(ItemEntity::getUUID));
        List<Job> jobs = new ArrayList<>();
        for (ItemEntity drop : lying) {
            WorldPos at = WorldSpaces.at(drop);
            BlockPos cell = at.block(level);
            Stances.of(level, Reach.workableCells(level, cell)).ifPresent(where -> jobs.add(new Job(
                "item:" + drop.getUUID(), new WorkSite.AtEntity(drop.getUUID(), at), cell, List.of(cell), List.of(),
                where)));
        }
        return jobs;
    }

    private Predicate<BlockPos> toClear(Predicate<BlockPos> member, Predicate<BlockState> kept) {
        return cell -> {
            if (!level.isLoaded(cell) || member.test(cell)) {
                return false;
            }
            BlockState state = level.getBlockState(cell);
            return clearable(level, cell, state) && !kept.test(state);
        };
    }

    static boolean clearable(ServerLevel level, BlockPos cell, BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof LiquidBlock)
            && state.getDestroySpeed(level, cell) >= 0.0F;
    }

    static boolean open(BlockState state) {
        return state.isAir() || state.canBeReplaced();
    }

    static boolean weed(BlockState state) {
        return FarmingContent.weed(state);
    }

    // A weed two tall is pulled by its lower half, which takes the upper with it.
    private static boolean weedAt(BlockState state) {
        return weed(state) && !(state.hasProperty(DoublePlantBlock.HALF)
            && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER);
    }

    private List<BlockPos> sorted(Comparator<BlockPos> order) {
        List<BlockPos> list = new ArrayList<>(cells);
        list.sort(order);
        return list;
    }

    private WorkSite site(BlockPos cell) {
        return new WorkSite.AtBlock(WorldPos.of(level, cell));
    }

    // Everywhere a body stands in reach of the cell, from a level below it to one above, but the cells the work
    // itself would leave nobody standing in.
    private Optional<Stances> stances(BlockPos target, Set<BlockPos> barred) {
        Set<BlockPos> found = new LinkedHashSet<>();
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -REACH; dz <= REACH; dz++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (!barred.contains(feet) && Reach.withinReach(feet, target) && footed.computeIfAbsent(feet,
                            at -> level.isLoaded(at) && Footing.withFeetAt(level, at))) {
                        found.add(feet);
                    }
                }
            }
        }
        return Stances.of(level, found);
    }

}
