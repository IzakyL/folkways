package io.github.izakyl.folkways.plugins.farming;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

sealed interface Habit {

    List<BlockPos> harvest(Crop crop, LevelReader level, BlockPos base);

    default boolean hasRoom(LevelReader level, BlockPos base, Set<BlockPos> sown) {
        return true;
    }

    default int reach() {
        return 1;
    }

    default boolean cutWhole() {
        return false;
    }

    default boolean cuts(Crop crop, BlockState state) {
        return crop.grows(state);
    }

    // What comes away with the cut: cells that go with the crop without being the crop.
    default List<BlockPos> canopy(LevelReader level, List<BlockPos> cut) {
        return List.of();
    }

    record Herb() implements Habit {

        @Override
        public List<BlockPos> harvest(Crop crop, LevelReader level, BlockPos base) {
            return crop.ripe(level, base) ? List.of(base) : List.of();
        }

        @Override
        public boolean cutWhole() {
            return true;
        }
    }

    record Cane(int retainHeight, int harvestHeight) implements Habit {

        @Override
        public List<BlockPos> harvest(Crop crop, LevelReader level, BlockPos base) {
            int height = 0;
            while (crop.grows(level.getBlockState(base.above(height)))) {
                height++;
            }
            if (height < harvestHeight) {
                return List.of();
            }
            List<BlockPos> column = new ArrayList<>(height - retainHeight);
            for (int level0 = height - 1; level0 >= retainHeight; level0--) {
                column.add(base.above(level0));
            }
            return List.copyOf(column);
        }
    }

    record Stem(Block fruit) implements Habit {

        @Override
        public List<BlockPos> harvest(Crop crop, LevelReader level, BlockPos base) {
            if (!crop.grows(level.getBlockState(base))) {
                return List.of();
            }
            List<BlockPos> fruits = new ArrayList<>();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos beside = base.relative(side);
                if (level.getBlockState(beside).is(fruit)) {
                    fruits.add(beside.immutable());
                }
            }
            return List.copyOf(fruits);
        }

        @Override
        public boolean cuts(Crop crop, BlockState state) {
            return state.is(fruit);
        }
    }

    record Tree(int radius, int height) implements Habit {

        private static final int MAX_BODY_CELLS = 128;

        private static final int MAX_CANOPY_CELLS = 256;

        private static final int LEAF_REACH = LeavesBlock.DECAY_DISTANCE - 1;

        private static final Comparator<BlockPos> UPWARD =
            Comparator.comparingInt((BlockPos cell) -> cell.getY())
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ);

        @Override
        public List<BlockPos> harvest(Crop crop, LevelReader level, BlockPos base) {
            List<BlockPos> trunk = new ArrayList<>();
            for (int up = 0; up < height; up++) {
                BlockPos cell = base.above(up);
                if (level.getBlockState(cell).is(BlockTags.LOGS)) {
                    trunk.add(cell.immutable());
                }
            }
            return trunk.isEmpty() ? List.of() : spread(level, base, trunk);
        }

        @Override
        public int reach() {
            return Math.max(1, radius);
        }

        @Override
        public boolean cuts(Crop crop, BlockState state) {
            return state.is(BlockTags.LOGS);
        }

        @Override
        public boolean hasRoom(LevelReader level, BlockPos base, Set<BlockPos> sown) {
            for (int up = 1; up < height; up++) {
                BlockState overhead = level.getBlockState(base.above(up));
                if (!overhead.isAir() && !overhead.is(BlockTags.REPLACEABLE_BY_TREES)) {
                    return false;
                }
            }
            for (BlockPos around : BlockPos.betweenClosed(
                    base.offset(-radius, 0, -radius), base.offset(radius, 0, radius))) {
                BlockState beside = level.getBlockState(around);
                if (beside.is(BlockTags.LOGS) || beside.is(BlockTags.SAPLINGS)) {
                    return false;
                }
            }
            for (BlockPos taken : sown) {
                if (claims(base, taken)) {
                    return false;
                }
            }
            return true;
        }

        // A leaf is this tree's when its nearest log is one of the tree's own: it is no farther, through leaves,
        // than the distance the leaf itself keeps to a log. A placed leaf never decays and is nobody's tree.
        @Override
        public List<BlockPos> canopy(LevelReader level, List<BlockPos> cut) {
            Set<BlockPos> seen = new LinkedHashSet<>(cut);
            List<BlockPos> leaves = new ArrayList<>();
            List<BlockPos> ring = cut;
            for (int step = 1; step <= LEAF_REACH && !ring.isEmpty(); step++) {
                List<BlockPos> next = new ArrayList<>();
                for (BlockPos cell : ring) {
                    for (Direction side : Direction.values()) {
                        BlockPos leaf = cell.relative(side);
                        if (!seen.add(leaf) || !level.hasChunkAt(leaf)
                            || !ownLeaf(level.getBlockState(leaf), step)) {
                            continue;
                        }
                        if (leaves.size() >= MAX_CANOPY_CELLS) {
                            return List.copyOf(leaves);
                        }
                        leaves.add(leaf);
                        next.add(leaf);
                    }
                }
                ring = next;
            }
            return List.copyOf(leaves);
        }

        static boolean leaf(BlockState state) {
            return state.is(BlockTags.LEAVES)
                && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
        }

        private static boolean ownLeaf(BlockState state, int step) {
            return leaf(state) && state.hasProperty(LeavesBlock.DISTANCE)
                && step <= state.getValue(LeavesBlock.DISTANCE);
        }

        boolean claims(BlockPos base, BlockPos cell) {
            return Math.abs(cell.getX() - base.getX()) <= radius
                && Math.abs(cell.getZ() - base.getZ()) <= radius
                && cell.getY() >= base.getY()
                && cell.getY() < base.getY() + height;
        }

        private List<BlockPos> spread(LevelReader level, BlockPos base, List<BlockPos> trunk) {
            Set<BlockPos> body = new LinkedHashSet<>(trunk);
            Deque<BlockPos> frontier = new ArrayDeque<>(trunk);
            while (!frontier.isEmpty() && body.size() < MAX_BODY_CELLS) {
                BlockPos cell = frontier.removeFirst();
                for (BlockPos around : BlockPos.betweenClosed(cell.offset(-1, -1, -1), cell.offset(1, 1, 1))) {
                    BlockPos next = around.immutable();
                    if (body.contains(next) || !claims(base, next)
                        || !level.getBlockState(next).is(BlockTags.LOGS)) {
                        continue;
                    }
                    body.add(next);
                    frontier.addLast(next);
                }
            }
            return body.stream().sorted(UPWARD).toList();
        }
    }
}
