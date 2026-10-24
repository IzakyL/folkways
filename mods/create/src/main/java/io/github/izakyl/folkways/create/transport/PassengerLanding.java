package io.github.izakyl.folkways.create.transport;

import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Structures;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

// Both a live journey and a cancelled ride need the same safe place to set a passenger down.
public final class PassengerLanding {

    private PassengerLanding() {
    }

    public static Optional<BlockPos> at(ServerLevel level, Stances.Cells platform, Set<WorldPos> onward) {
        Set<BlockPos> landing = cellsIn(level, platform.cells());
        if (landing.isEmpty()) {
            return Optional.empty();
        }
        Set<BlockPos> goals = cellsIn(level, onward);
        if (goals.isEmpty()) {
            goals = landing;
        }
        AABB about = null;
        for (BlockPos cell : landing) {
            AABB one = new AABB(cell).inflate(1.0D);
            about = about == null ? one : about.minmax(one);
        }
        List<AABB> obstacles = Structures.obstacles(level, about);
        Set<BlockPos> towards = goals;
        return landing.stream().filter(cell -> level.isLoaded(cell) && Footing.withFeetAt(level, cell)
                && !Structures.blocked(obstacles, cell) && !Structures.blocked(obstacles, cell.above()))
            .min(java.util.Comparator.<BlockPos>comparingDouble(cell -> distance(towards, cell))
                .thenComparingLong(BlockPos::asLong));
    }

    private static Set<BlockPos> cellsIn(ServerLevel level, Set<WorldPos> cells) {
        Set<BlockPos> here = new LinkedHashSet<>();
        for (WorldPos cell : cells) {
            if (cell.in(level)) {
                here.add(cell.block(level));
            }
        }
        return here;
    }

    private static double distance(Set<BlockPos> goals, BlockPos cell) {
        return goals.stream().mapToDouble(cell::distSqr).min().orElse(Double.MAX_VALUE);
    }
}
