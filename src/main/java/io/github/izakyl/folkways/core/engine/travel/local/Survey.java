package io.github.izakyl.folkways.core.engine.travel.local;

import io.github.izakyl.folkways.core.api.resident.Search;
import io.github.izakyl.folkways.core.api.terms.ObstructedPathRegion;
import io.github.izakyl.folkways.core.api.terms.Structures;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.Target;

final class Survey {

    private Survey() {
    }

    static Section of(ServerLevel level, long key, Walker walker, Map<BlockPos, BlockState> changes) {
        BlockPos min = Section.origin(key);
        BlockPos max = min.offset(Section.EDGE - 1, Section.EDGE - 1, Section.EDGE - 1);
        BlockPos from = min.offset(-Section.READ_ACROSS, -Section.READ_BELOW, -Section.READ_ACROSS);
        BlockPos to = max.offset(Section.READ_ACROSS, Section.READ_ABOVE, Section.READ_ACROSS);
        if (!level.hasChunksAt(from, to)) {
            return Section.UNKNOWN;
        }
        PathNavigationRegion region = Structures.region(level, from, to);
        if (!changes.isEmpty()) {
            region = new ObstructedPathRegion(level, from, region, changes);
        }
        NodeEvaluator evaluator = walker.locomotion().newEvaluator(new Search(Optional.empty()));
        PathfindingContext context = new PathfindingContext(region, walker.body());
        LongOpenHashSet stands = new LongOpenHashSet();
        Long2ObjectOpenHashMap<long[]> steps = new Long2ObjectOpenHashMap<>();
        Node[] buffer = new Node[32];
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        evaluator.prepare(region, walker.body());
        try {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    for (int y = min.getY(); y <= max.getY(); y++) {
                        if (region.getBlockState(probe.set(x, y, z)).isAir()
                            && region.getBlockState(probe.set(x, y - 1, z)).isAir()) {
                            continue;
                        }
                        PathType type = evaluator.getPathTypeOfMob(context, x, y, z, walker.body());
                        if (type == PathType.OPEN || walker.body().getPathfindingMalus(type) < 0.0F) {
                            continue;
                        }
                        long cell = BlockPos.asLong(x, y, z);
                        stands.add(cell);
                        int count = evaluator.getNeighbors(buffer, new Target(x, y, z));
                        LongArrayList out = new LongArrayList(count);
                        for (int at = 0; at < count; at++) {
                            out.add(BlockPos.asLong(buffer[at].x, buffer[at].y, buffer[at].z));
                        }
                        steps.put(cell, out.toLongArray());
                    }
                }
            }
        } finally {
            evaluator.done();
        }
        return new Section(true, stands, steps);
    }
}
