package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.terms.BlockSketch;
import io.github.izakyl.folkways.core.api.terms.Loaded;
import io.github.izakyl.folkways.plugins.build.Footprints;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

// Local support probes only: no world edits, item searches, or walks through an entire falling structure.
final class ClearOrder {

    static final int SIDE = 0;
    static final int SUPPORTED = 1;
    static final int ORDINARY = 2;

    private final ServerLevel level;
    private final BlockSketch without;
    private final Map<BlockPos, Integer> priorities = new HashMap<>();

    ClearOrder(ServerLevel level) {
        this.level = level;
        this.without = new BlockSketch(level);
    }

    // Reciprocal parts disappear together, so a door or bed is one small operation, not two competing hands.
    // Growing columns stay individual jobs: following their whole footprint would make tall plants unbounded.
    static List<BlockPos> parts(ServerLevel level, BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            BlockPos lower = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                ? cell : cell.below();
            List<BlockPos> parts = new ArrayList<>(2);
            for (BlockPos part : List.of(lower, lower.above())) {
                if (level.isLoaded(part)) {
                    BlockState standing = level.getBlockState(part);
                    DoubleBlockHalf half = part.equals(lower) ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER;
                    if (standing.is(state.getBlock()) && standing.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == half) {
                        parts.add(part);
                    }
                }
            }
            return List.copyOf(parts);
        }
        if (!(state.getBlock() instanceof BedBlock)
                && !(state.getBlock() instanceof PistonHeadBlock)
                && !(state.getBlock() instanceof PistonBaseBlock && state.getValue(PistonBaseBlock.EXTENDED))) {
            return List.of(cell);
        }
        Map<BlockPos, BlockState> footprint = Footprints.of(level::getBlockState, cell, state).cells();
        if (footprint.size() > 2) {
            return List.of(cell);
        }
        List<BlockPos> parts = new ArrayList<>(2);
        footprint.forEach((part, expected) -> {
            if (level.isLoaded(part) && level.getBlockState(part) == expected) {
                parts.add(part);
            }
        });
        return List.copyOf(parts);
    }

    int priority(BlockPos cell) {
        return priorities.computeIfAbsent(cell, this::findPriority);
    }

    int priority(List<BlockPos> parts) {
        return parts.stream().mapToInt(this::priority).min().orElse(ORDINARY);
    }

    private int findPriority(BlockPos cell) {
        if (!Loaded.around(level, cell, 2)) {
            return ORDINARY;
        }
        BlockState state = level.getBlockState(cell);
        if (state.getBlock() instanceof ScaffoldingBlock) {
            return state.getValue(ScaffoldingBlock.DISTANCE) > 0 ? SIDE : SUPPORTED;
        }
        if (!stays(level, cell, state)) {
            return ORDINARY;
        }
        int priority = ORDINARY;
        for (Direction direction : Direction.values()) {
            BlockPos support = cell.relative(direction);
            if (!level.isLoaded(support)) {
                continue;
            }
            without.draw(support, level.getFluidState(support).createLegacyBlock());
            try {
                if (!stays(without, cell, state)) {
                    priority = Math.min(priority, direction.getAxis().isHorizontal() ? SIDE : SUPPORTED);
                }
            } finally {
                without.lift(support);
            }
        }
        return priority;
    }

    boolean blocked(List<BlockPos> parts) {
        if (parts.stream().anyMatch(part -> !Loaded.around(level, part, 2))) {
            return true;
        }
        Set<BlockPos> removed = Set.copyOf(parts);
        Set<BlockPos> neighbors = new LinkedHashSet<>();
        for (BlockPos part : parts) {
            without.draw(part, level.getFluidState(part).createLegacyBlock());
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = part.relative(direction);
                if (!removed.contains(neighbor) && !level.isOutsideBuildHeight(neighbor)) {
                    neighbors.add(neighbor);
                }
            }
        }
        try {
            for (BlockPos neighbor : neighbors) {
                // Do not load a chunk, or assume that an unseen attachment has gone.
                if (!level.isLoaded(neighbor)) {
                    return true;
                }
                BlockState state = level.getBlockState(neighbor);
                if (state.isAir() || state.liquid()) {
                    continue;
                }
                if (stays(level, neighbor, state) && !stays(without, neighbor, state)) {
                    return true;
                }
                if (state.getBlock() instanceof ScaffoldingBlock) {
                    for (BlockPos support : parts) {
                        // Scaffold distance updates happen on later ticks. Its current neighbors' distances
                        // can still describe the very support being removed, so never use them as a new route.
                        if (neighbor.below().equals(support)) {
                            return true;
                        }
                        BlockState holding = level.getBlockState(support);
                        if (neighbor.getY() == support.getY() && holding.getBlock() instanceof ScaffoldingBlock
                                && state.getValue(ScaffoldingBlock.DISTANCE)
                                    > holding.getValue(ScaffoldingBlock.DISTANCE)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } finally {
            parts.forEach(without::lift);
        }
    }

    private static boolean stays(LevelReader level, BlockPos cell, BlockState state) {
        return state.canSurvive(level, cell)
            && !(state.getBlock() instanceof FallingBlock && FallingBlock.isFree(level.getBlockState(cell.below())));
    }
}
