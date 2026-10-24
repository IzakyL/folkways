package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.resident.Search;
import io.github.izakyl.folkways.core.api.resident.Locomotion;
import io.github.izakyl.folkways.core.api.terms.Closures;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.Structures;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.travel.PathfindingLoad;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;

final class Look {

    private Look() {
    }

    // This is only a definite native BLOCKED result, not a generic human-footing rule. In
    // particular OPEN can be valid for flight, and entity congestion is not terrain evidence.
    static boolean blocked(ServerLevel level, WorldPos cell, Locomotion moving, Mob body) {
        Ground ground = Ground.of(level, cell.realm()).orElse(null);
        Bulk bulk = Bulk.of(body);
        if (ground == null || bulk.width() < 1 || bulk.height() < 1 || bulk.width() > 16 || bulk.height() > 16) return false;
        BlockPos at = ground.toStorage(cell.cell());
        if (ground.toLocal(at).filter(cell.cell()::equals).isEmpty()) return false;
        BlockPos from = at.offset(-8, -8, -8);
        BlockPos to = at.offset(bulk.width() + 8, bulk.height() + 8, bulk.width() + 8);
        if (!level.hasChunksAt(from, to)) return false;
        PathNavigationRegion region = Structures.region(level, from, to);
        NodeEvaluator evaluator = moving.newEvaluator(Search.from(at));
        evaluator.prepare(region, body);
        try {
            return evaluator.getPathTypeOfMob(new PathfindingContext(region, body), at.getX(), at.getY(), at.getZ(), body)
                == PathType.BLOCKED;
        } finally {
            evaluator.done();
        }
    }

    static Optional<Reading> at(ServerLevel level, Realm realm, Roamer roamer, long from,
                                Set<BlockPos> arriving, BlockPos aim, boolean wide) {
        return at(level, realm, roamer, from, arriving, aim, wide, Sweep.MOST_VISITED);
    }

    static Optional<Reading> at(ServerLevel level, Realm realm, Roamer roamer, long from,
                                Set<BlockPos> arriving, BlockPos aim, boolean wide, int limit) {
        return at(level, realm, roamer, from, arriving, cell -> false, aim, wide, limit);
    }

    static Optional<Reading> at(ServerLevel level, Realm realm, Roamer roamer, long from,
                                Set<BlockPos> arriving, LongPredicate landing, BlockPos aim, boolean wide,
                                int limit) {
        Ground standing = Ground.of(level, realm).orElse(null);
        if (standing == null) {

            return Optional.empty();
        }
        BlockPos at = standing.toStorage(BlockPos.of(from));
        int reach = (int) Sweep.RANGE + Sweep.MARGIN;
        BlockPos min = at.offset(-reach, -reach, -reach);
        BlockPos max = at.offset(reach, reach, reach);
        Ground here = standing;
        PathNavigationRegion region = Structures.region(level, min, max);
        PathfindingLoad.searchStarting(level);
        Set<BlockPos> ends = new LinkedHashSet<>(arriving.size());
        for (BlockPos goal : arriving) {
            ends.add(here.toStorage(goal));
        }
        LongPredicate local = here instanceof Ground.Open ? landing
            : storage -> here.toLocal(BlockPos.of(storage)).map(cell -> landing.test(cell.asLong())).orElse(false);
        return Optional.of(new Reading(here, Sweep.from(region, roamer.body(),
            roamer.locomotion().newEvaluator(Search.from(at)), ends, local, shut(level, here), here.toStorage(aim),
            wide, limit)));
    }

    // Closures stand on the open ground of the level, never aboard.
    private static LongPredicate shut(ServerLevel level, Ground here) {
        List<Closures.Closed> closed = Closures.in(level);
        if (closed.isEmpty() || !(here instanceof Ground.Open)) {
            return cell -> false;
        }
        return cell -> {
            BlockPos at = BlockPos.of(cell);
            for (Closures.Closed one : closed) {
                if (one.holds(at)) {
                    return true;
                }
            }
            return false;
        };
    }
}
