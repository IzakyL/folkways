package io.github.izakyl.folkways.plugins.groundwork;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

// A small body of horizontal logs resting on the ground, away from trunks and leaves.
final class FallenLogs {

    private static final int MAX_LOGS = 64;
    private static final TagKey<Block> LOGS = tag("fallen_logs");
    private static final TagKey<Block> DECORATIONS = tag("fallen_log_decorations");

    private final LevelReader level;
    private final Predicate<BlockPos> spared;
    private final Map<BlockPos, Boolean> found = new HashMap<>();

    FallenLogs(LevelReader level, Predicate<BlockPos> spared) {
        this.level = level;
        this.spared = spared;
    }

    boolean at(BlockPos start) {
        if (found.containsKey(start)) {
            return found.get(start);
        }
        Set<BlockPos> logs = new LinkedHashSet<>();
        var pending = new ArrayDeque<BlockPos>();
        logs.add(start);
        pending.add(start);
        boolean fallen = true;
        while (!pending.isEmpty() && fallen) {
            BlockPos cell = pending.removeFirst();
            BlockPos below = cell.below();
            if (!level.hasChunkAt(cell) || !level.hasChunkAt(below)
                || !horizontal(level.getBlockState(cell)) || spared.test(cell) || spared.test(below)
                || !level.getBlockState(below).isSolid() || log(level.getBlockState(below))) {
                fallen = false;
                break;
            }
            // Follow diagonal joins too, as tree felling does. Unknown or oversized bodies stay standing.
            for (BlockPos around : BlockPos.betweenClosed(cell.offset(-1, -1, -1), cell.offset(1, 1, 1))) {
                if (!level.hasChunkAt(around)) {
                    fallen = false;
                    break;
                }
                BlockState state = level.getBlockState(around);
                if (state.is(BlockTags.LEAVES)) {
                    fallen = false;
                    break;
                }
                if (log(state) && !logs.contains(around)) {
                    if (logs.size() >= MAX_LOGS) {
                        fallen = false;
                        break;
                    }
                    BlockPos next = around.immutable();
                    logs.add(next);
                    pending.add(next);
                }
            }
        }
        for (BlockPos log : logs) {
            found.put(log, fallen);
        }
        return fallen;
    }

    List<BlockPos> decorations(BlockPos cell) {
        return List.of(cell.above(), cell.north(), cell.south(), cell.west(), cell.east()).stream()
            .filter(at -> level.hasChunkAt(at) && decoration(level.getBlockState(at))).toList();
    }

    static boolean horizontal(BlockState state) {
        return log(state) && state.hasProperty(BlockStateProperties.AXIS)
            && state.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y;
    }

    static boolean decoration(BlockState state) {
        return state.is(DECORATIONS);
    }

    private static boolean log(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(LOGS);
    }

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("folkways", path));
    }
}
