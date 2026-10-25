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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Weeds, trees and loose stacks in a zone, read off the world one sweep at a time.
final class Survey {

    // How far a body stands from the cell it works: as far as its hands reach.
    private static final int REACH = (int) Math.floor(Reach.PLAYER_BLOCK_REACH);

    private static final Comparator<BlockPos> UPWARD =
        Comparator.comparingInt((BlockPos cell) -> cell.getY())
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ);

    private boolean discovering;

    Survey discovering() {
        discovering = true;
        return this;
    }

    private final ServerLevel level;
    private final Set<BlockPos> cells;
    private final Map<BlockPos, Boolean> footed = new HashMap<>();

    Survey(ServerLevel level, Set<BlockPos> cells) {
        this.level = level;
        this.cells = cells;
    }

    List<Job> weed(Predicate<BlockState> pulled) {
        List<Job> jobs = new ArrayList<>();
        for (BlockPos cell : sorted(UPWARD)) {
            if (level.isLoaded(cell) && weedAt(level.getBlockState(cell), pulled)) {
                stances(cell).ifPresent(where -> jobs.add(Job.at(cell, site(cell), where)));
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
            Optional<Stances> where = stances(cell);
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
            Optional<Stances> where = discovering ? Optional.of(Stances.WHEREVER)
                : Stances.of(level, Reach.workableCells(level, cell));
            where.ifPresent(footings -> jobs.add(new Job(
                "item:" + drop.getUUID(), new WorkSite.AtEntity(drop.getUUID(), at), cell, List.of(cell), List.of(),
                footings)));
        }
        return jobs;
    }

    // What a zone pulls when it weeds: its grass, its flowers, or both.
    static Predicate<BlockState> weeds(boolean grass, boolean flowers) {
        return state -> FarmingContent.weed(state, grass, flowers);
    }

    // A weed two tall is pulled by its lower half, which takes the upper with it.
    private static boolean weedAt(BlockState state, Predicate<BlockState> pulled) {
        return pulled.test(state) && !(state.hasProperty(DoublePlantBlock.HALF)
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

    // Everywhere a body stands in reach of the cell, from a level below it to one above.
    private Optional<Stances> stances(BlockPos target) {
        if (discovering) {
            return Optional.of(Stances.WHEREVER);
        }
        Set<BlockPos> found = new LinkedHashSet<>();
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -REACH; dz <= REACH; dz++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (Reach.withinReach(feet, target) && footed.computeIfAbsent(feet,
                            at -> level.isLoaded(at) && Footing.withFeetAt(level, at))) {
                        found.add(feet);
                    }
                }
            }
        }
        return Stances.of(level, found);
    }

}
