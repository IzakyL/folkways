package io.github.izakyl.folkways.core.engine.travel.local;

import io.github.izakyl.folkways.core.api.ground.Ground;
import io.github.izakyl.folkways.core.api.resident.Search;
import io.github.izakyl.folkways.core.api.terms.ObstructedPathRegion;
import io.github.izakyl.folkways.core.api.terms.Structures;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
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
        Read read = new Read(level, key, walker, changes);
        read.advance(Ground.Budget.unlimited());
        return read.result;
    }

    // A live evaluator never survives a slice: prepare/done can temporarily change a real Mob's state.
    // Only the region, cursor and geometry survive; the watch discards them on a terrain revision.
    static final class Read {
        private final ServerLevel level;
        private Walker walker;
        private final Map<BlockPos, BlockState> changes;
        private final BlockPos min;
        private final BlockPos from;
        private final BlockPos to;
        private final LongOpenHashSet stands = new LongOpenHashSet();
        private final Long2ObjectOpenHashMap<long[]> steps = new Long2ObjectOpenHashMap<>();
        @Nullable
        private PathNavigationRegion region;
        @Nullable
        private Section result;
        private int cursor;

        Read(ServerLevel level, long key, Walker walker, Map<BlockPos, BlockState> changes) {
            this.level = level;
            this.walker = walker;
            this.changes = changes;
            min = Section.origin(key);
            BlockPos max = min.offset(Section.EDGE - 1, Section.EDGE - 1, Section.EDGE - 1);
            from = min.offset(-Section.READ_ACROSS, -Section.READ_BELOW, -Section.READ_ACROSS);
            to = max.offset(Section.READ_ACROSS, Section.READ_ABOVE, Section.READ_ACROSS);
        }

        @Nullable
        Section result() {
            return result;
        }

        void use(Walker current) {
            walker = current;
        }

        boolean advance(Ground.Budget budget) {
            if (result != null) {
                return true;
            }
            if (budget.exhausted()) {
                return false;
            }
            if (region == null) {
                if (!budget.tryStep()) {
                    return false;
                }
                if (!level.hasChunksAt(from, to)) {
                    result = Section.UNKNOWN;
                    return true;
                }
                region = Structures.region(level, from, to);
                if (!changes.isEmpty()) {
                    region = new ObstructedPathRegion(level, from, region, changes);
                }
            }
            if (budget.exhausted()) {
                return false;
            }
            NodeEvaluator evaluator = walker.locomotion().newEvaluator(new Search(Optional.empty()));
            PathfindingContext context = new PathfindingContext(region, walker.body());
            Node[] buffer = new Node[32];
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
            evaluator.prepare(region, walker.body());
            try {
                while (cursor < Section.EDGE * Section.EDGE * Section.EDGE) {
                    if (!budget.tryStep()) {
                        return false;
                    }
                    int x = min.getX() + (cursor >> (Section.SHIFT * 2));
                    int z = min.getZ() + ((cursor >> Section.SHIFT) & (Section.EDGE - 1));
                    int y = min.getY() + (cursor & (Section.EDGE - 1));
                    cursor++;
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
                    long[] out = new long[count];
                    for (int at = 0; at < count; at++) {
                        out[at] = BlockPos.asLong(buffer[at].x, buffer[at].y, buffer[at].z);
                    }
                    steps.put(cell, out);
                }
            } finally {
                evaluator.done();
            }
            result = new Section(true, stands, steps);
            return true;
        }
    }
}
