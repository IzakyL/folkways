package io.github.izakyl.folkways.vanilla.person.ride;

import io.github.izakyl.folkways.core.world.api.Reach;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Reserve before the ridden approach so simultaneous arrivals cannot choose the same parking space.
final class HorseParking {
    private static final double SPACING = 2.0;
    // Navigation completes within about one block of the cell centre. Leave a margin for both arrivals.
    private static final double ARRIVAL = 1.1;
    private static final int RADIUS = 6;
    private static final Map<AbstractHorse, BlockPos> RESERVED = new WeakHashMap<>();

    private HorseParking() {
    }

    static Path reserve(ServerLevel level, AbstractHorse horse, Path approach, Set<BlockPos> destinations) {
        // Paths and footings use the cell above a short floor, including dirt paths and farmland.
        BlockPos end = BlockPos.containing(horse.getX(), horse.getY() + .5, horse.getZ());
        var horses = level.getEntitiesOfClass(AbstractHorse.class, new AABB(end).inflate(RADIUS + 5),
            other -> other != horse && other.isAlive());
        var candidates = Reach.footingsAround(level, end, RADIUS, 1).stream()
            .filter(cell -> cell.getY() == end.getY())
            .filter(cell -> !destinations.contains(cell) && !byDoor(level, cell))
            // Leave a pedestrian exit beside the horse as well as clearance for its own body.
            .filter(cell -> level.noCollision(horse, new AABB(cell.getX() - 1.3, cell.getY() + .01,
                cell.getZ() - 1.3, cell.getX() + 2.3, cell.getY() + 2, cell.getZ() + 2.3)))
            .filter(cell -> horses.stream().noneMatch(other ->
                horizontal(cell.getBottomCenter(), other.position()) < square(SPACING + ARRIVAL)))
            .filter(cell -> RESERVED.entrySet().stream().noneMatch(entry -> entry.getKey() != horse
                && entry.getKey().isAlive() && entry.getKey().level() == level
                && horizontal(cell.getBottomCenter(), entry.getValue().getBottomCenter())
                    < square(SPACING + 2 * ARRIVAL)))
            .sorted(Comparator.comparingDouble((BlockPos cell) -> score(level, approach, end, cell))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).toList();
        for (BlockPos cell : candidates) {
            Path path = horse.getNavigation().createPath(cell, 0);
            if (path != null && path.canReach()) {
                RESERVED.put(horse, cell);
                return path;
            }
        }
        return null;
    }

    private static double score(ServerLevel level, Path approach, BlockPos end, BlockPos cell) {
        double score = cell.distSqr(end);
        var floor = level.getBlockState(cell.below());
        if (floor.is(Blocks.DIRT_PATH) || floor.is(Blocks.COARSE_DIRT)) {
            score += 32;
        }
        for (int i = 0; approach != null && i < approach.getNodeCount(); i++) {
            if (horizontal(cell.getBottomCenter(), approach.getNode(i).asBlockPos().getBottomCenter()) < 2.25) {
                score += 32;
                break;
            }
        }
        return score;
    }

    private static boolean byDoor(ServerLevel level, BlockPos cell) {
        for (BlockPos at : BlockPos.betweenClosed(cell.offset(-2, -1, -2), cell.offset(2, 1, 2))) {
            if (level.getBlockState(at).is(BlockTags.DOORS)
                    && horizontal(cell.getBottomCenter(), at.getBottomCenter()) < 6.25) {
                return true;
            }
        }
        return false;
    }

    private static double horizontal(Vec3 from, Vec3 to) {
        return from.subtract(to).horizontalDistanceSqr();
    }

    private static double square(double value) {
        return value * value;
    }

    static void release(AbstractHorse horse) {
        RESERVED.remove(horse);
    }
}
