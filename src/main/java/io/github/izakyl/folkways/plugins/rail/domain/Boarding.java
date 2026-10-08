package io.github.izakyl.folkways.plugins.rail.domain;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.terms.Keepouts;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.Structures;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

public final class Boarding {

    private static final int RISE = 2;

    private static final int MOST = 64;

    private Boarding() {
    }

    public static int seatTicks() {
        return FolkwaysConfig.seatTicks();
    }

    public static Optional<Stances.Cells> footings(ServerLevel level, BlockPos anchor, int span) {
        return standing(level, anchor, span, feet -> true);
    }

    public static Optional<Stances.Cells> platform(ServerLevel level, Berth berth) {
        int span = Mth.ceil(Math.max(Math.max(-berth.from(), berth.to()), berth.halfWidth() + Berth.SIDE)) + 1;
        return standing(level, berth.at().cell(), span, berth::alongside);
    }

    private static Optional<Stances.Cells> standing(ServerLevel level, BlockPos anchor, int span,
            Predicate<BlockPos> keep) {
        if (!level.isLoaded(anchor)) {
            return Optional.empty();
        }
        List<AABB> carriages = Structures.obstacles(level,
            new AABB(anchor).inflate(span + 1.0D, RISE + 1.0D, span + 1.0D));
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos feet : Reach.footingsAround(level, anchor, span, RISE)) {
            if (!keep.test(feet) || Keepouts.forbidden(level, feet) || underAStructure(carriages, feet) || underAStructure(carriages, feet.above())) {
                continue;
            }
            found.add(feet);
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        found.sort((left, right) -> Double.compare(left.distSqr(anchor), right.distSqr(anchor)));
        Set<WorldPos> cells = new LinkedHashSet<>();
        for (BlockPos feet : found.subList(0, Math.min(MOST, found.size()))) {
            cells.add(WorldPos.of(level, feet));
        }
        return Optional.of(new Stances.Cells(cells));
    }

    public static boolean underAStructure(List<AABB> frames, BlockPos cell) {
        return Structures.blocked(frames, cell);
    }
}
