package io.github.izakyl.folkways.core.travel.internal.graph;

import io.github.izakyl.folkways.core.resident.api.Search;
import io.github.izakyl.folkways.core.travel.internal.PathfindingLoad;
import io.github.izakyl.folkways.core.world.api.Closures;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.Structures;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.PathNavigationRegion;

final class Look {

    private Look() {
    }

    static Optional<TerrainReading> at(ServerLevel level, Realm realm, Roamer roamer, long from,
                                Set<BlockPos> arriving, BlockPos aim, boolean wide) {
        return at(level, realm, roamer, from, arriving, aim, wide, Sweep.MOST_VISITED);
    }

    static Optional<TerrainReading> at(ServerLevel level, Realm realm, Roamer roamer, long from,
                                Set<BlockPos> arriving, BlockPos aim, boolean wide, int limit) {
        return at(level, realm, roamer, from, arriving, cell -> false, aim, wide, limit);
    }

    static Optional<TerrainReading> at(ServerLevel level, Realm realm, Roamer roamer, long from,
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
        return Optional.of(new TerrainReading(here, Sweep.from(region, roamer.body(),
            roamer.locomotion().newEvaluator(Search.from(at)), ends, local, shut(level, realm, here), here.toStorage(aim),
            wide, limit)));
    }

    private static LongPredicate shut(ServerLevel level, Realm realm, Ground here) {
        var boxes = Closures.in(level).stream().filter(one -> one.realm().equals(realm))
            .map(Closures.Closed::box).toList();
        if (boxes.isEmpty()) {
            return cell -> false;
        }
        return cell -> here.toLocal(BlockPos.of(cell))
            .filter(at -> boxes.stream().anyMatch(box -> box.isInside(at))).isPresent();
    }
}
