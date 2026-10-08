package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.front.api.FrontView;
import io.github.izakyl.folkways.front.api.PathView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

// Lays stops along a drawn patrol route, a ward's radius apart at most, so the wards they leave
// cover the route from end to end.
final class Beats {

    private static final int MIN_SPACING = 4;

    // How far up or down from a drawn point a patroller's feet are looked for, nearest first.
    private static final int[] RISE = {1, 0, 2, -1, 3, -2, 4, -3};

    private Beats() {
    }

    static Map<UUID, List<Stop>> charted(FrontView front, ServerLevel level) {
        Map<UUID, List<Stop>> found = new LinkedHashMap<>();
        for (PathView path : front.paths()) {
            if (!path.delegation().equals(PatrolContent.BEAT) || !path.dimension().equals(level.dimension())) {
                continue;
            }
            List<Stop> stops = along(level, path.points());
            if (!stops.isEmpty()) {
                found.put(path.id(), stops);
            }
        }
        return Map.copyOf(found);
    }

    static List<Stop> along(ServerLevel level, List<BlockPos> points) {
        int spacing = Math.max(MIN_SPACING, FolkwaysConfig.patrolWardRadius());
        List<BlockPos> marks = new ArrayList<>();
        marks.add(points.getFirst());
        for (int at = 1; at < points.size(); at++) {
            BlockPos from = points.get(at - 1);
            BlockPos to = points.get(at);
            double length = Math.sqrt(from.distSqr(to));
            int steps = Math.max(1, Mth.ceil(length / spacing));
            for (int step = 1; step <= steps; step++) {
                double part = (double) step / steps;
                marks.add(BlockPos.containing(
                    Mth.lerp(part, from.getX(), to.getX()) + 0.5,
                    Mth.lerp(part, from.getY(), to.getY()) + 0.5,
                    Mth.lerp(part, from.getZ(), to.getZ()) + 0.5));
            }
        }
        List<Stop> stops = new ArrayList<>(marks.size());
        BlockPos last = null;
        for (BlockPos mark : marks) {
            Optional<BlockPos> feet = feetNear(level, mark);
            if (feet.isEmpty() || feet.get().equals(last)) {
                continue;
            }
            last = feet.get();
            stops.add(new Stop(WorldPos.of(level, last), Stances.at(level, last)));
        }
        return List.copyOf(stops);
    }

    private static Optional<BlockPos> feetNear(ServerLevel level, BlockPos mark) {
        for (int rise : RISE) {
            BlockPos feet = mark.above(rise);
            if (level.isLoaded(feet) && Footing.withFeetAt(level, feet)) {
                return Optional.of(feet.immutable());
            }
        }
        return Optional.empty();
    }
}
