package io.github.izakyl.folkways.create.transport;

import com.simibubi.create.content.trains.track.ITrackBlock;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.Structures;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Both a live journey and a cancelled ride need the same safe place to set a passenger down.
public final class PassengerLanding {

    private static final int NEARBY_RANGE = 8;

    private static final int PLATFORM_RANGE = 16;

    public static final int PLATFORM_FALLBACK_TICKS = 60 * 20;

    private PassengerLanding() {
    }

    public static Optional<BlockPos> nearby(ServerLevel level, Mob body) {
        return nearby(level, body.blockPosition(), NEARBY_RANGE);
    }

    public static Optional<BlockPos> widerSearch(ServerLevel level, Mob body) {
        return nearby(level, body.blockPosition(), PLATFORM_RANGE);
    }

    private static Optional<BlockPos> nearby(ServerLevel level, BlockPos origin, int range) {
        BlockPos min = origin.offset(-range, -4, -range);
        BlockPos max = origin.offset(range, 3, range);
        List<AABB> obstacles = Structures.obstacles(level, AABB.encapsulatingFullBlocks(min, max.above()));
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        boolean bestOnTrack = true;
        for (BlockPos cell : BlockPos.betweenClosed(min, max)) {
            double distance = origin.distSqr(cell);
            if (distance > range * range || !safe(level, obstacles, cell)) {
                continue;
            }
            boolean onTrack = onTrack(level, cell) || onTrack(level, cell.below());
            if (best == null || bestOnTrack && !onTrack || bestOnTrack == onTrack && distance < bestDistance) {
                best = cell.immutable();
                bestDistance = distance;
                bestOnTrack = onTrack;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean onTrack(ServerLevel level, BlockPos cell) {
        return level.getBlockState(cell).getBlock() instanceof ITrackBlock;
    }

    public static Optional<BlockPos> localPlatform(ServerLevel level, Mob body, Stances.Cells platform) {
        BlockPos origin = body.blockPosition();
        Set<BlockPos> local = cellsIn(level, platform.cells());
        local.removeIf(cell -> cell.distSqr(origin) > PLATFORM_RANGE * PLATFORM_RANGE);
        return at(level, local, Set.of(origin));
    }

    public static void place(Mob body, BlockPos cell) {
        Vec3 feet = Vec3.atBottomCenterOf(cell);
        body.teleportTo(feet.x, feet.y, feet.z);
    }

    public static Optional<BlockPos> at(ServerLevel level, Stances.Cells platform, Set<WorldPos> onward) {
        return at(level, cellsIn(level, platform.cells()), cellsIn(level, onward));
    }

    private static Optional<BlockPos> at(ServerLevel level, Set<BlockPos> landing, Set<BlockPos> goals) {
        if (landing.isEmpty()) {
            return Optional.empty();
        }
        AABB about = null;
        for (BlockPos cell : landing) {
            AABB one = new AABB(cell).inflate(1.0D);
            about = about == null ? one : about.minmax(one);
        }
        List<AABB> obstacles = Structures.obstacles(level, about);
        Set<BlockPos> towards = goals.isEmpty() ? landing : goals;
        return landing.stream().filter(cell -> safe(level, obstacles, cell))
            .min(Comparator.<BlockPos>comparingDouble(cell -> distance(towards, cell))
                .thenComparingLong(BlockPos::asLong));
    }

    private static boolean safe(ServerLevel level, List<AABB> obstacles, BlockPos cell) {
        return level.isLoaded(cell) && level.getWorldBorder().isWithinBounds(cell)
            && level.getFluidState(cell).isEmpty() && level.getFluidState(cell.above()).isEmpty()
            && Footing.withFeetAt(level, cell)
            && !hazard(level.getBlockState(cell)) && !hazard(level.getBlockState(cell.above()))
            && !hazard(level.getBlockState(cell.below()))
            && !Structures.blocked(obstacles, cell) && !Structures.blocked(obstacles, cell.above());
    }

    private static boolean hazard(BlockState state) {
        return state.is(BlockTags.FIRE) || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW) || CampfireBlock.isLitCampfire(state);
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
