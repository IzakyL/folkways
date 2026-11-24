package io.github.izakyl.folkways.core.shell.create.elevator;

import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

public final class ElevatorApproach {
    private ElevatorApproach() {
    }

    private static Set<BlockPos> platform(ServerLevel level, Stances.Cells boarding) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (WorldPos pos : boarding.cells()) {
            if (pos.in(level)) {
                cells.add(pos.block(level));
            }
        }
        return cells;
    }

    public static boolean atPlatform(ServerLevel level, Mob body, Stances.Cells boarding) {
        return Reach.stanceHolding(body.position(), platform(level, boarding)).isPresent();
    }

    public static void walkToPlatform(ServerLevel level, Mob body, Stances.Cells boarding) {
        if (!body.getNavigation().isDone()) {
            return;
        }
        Set<BlockPos> goals = platform(level, boarding);
        var traffic = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, body.getBoundingBox().inflate(12));
        goals.removeIf(cell -> {
            double half = body.getBbWidth() / 2.0D;
            var space = new net.minecraft.world.phys.AABB(cell.getX() + 0.5D - half, cell.getY(), cell.getZ() + 0.5D - half,
                cell.getX() + 0.5D + half, cell.getY() + body.getBbHeight(), cell.getZ() + 0.5D + half);
            return traffic.stream().anyMatch(other -> other != body && other.isAlive() && other.getBoundingBox().intersects(space));
        });
        if (goals.isEmpty()) {
            return;
        }
        var path = body.getNavigation().createPath(goals, 0);
        if (path != null) {
            body.getNavigation().moveTo(path, 1.0D);
        }
    }
}
