package io.github.izakyl.folkways.create.elevator;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import io.github.izakyl.folkways.core.api.terms.ObstructedPathRegion;
import io.github.izakyl.folkways.core.api.terms.Structures;
import io.github.izakyl.folkways.plugins.person.walk.ResidentNodeEvaluator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A temporary pathfinding view of the stopped cabin, never fake blocks in the actual level. */
final class CabinNavigation {
    private record Attempt(long at, Set<BlockPos> goals, boolean reachable) { }
    private static final Map<Mob, Attempt> ATTEMPTS = new WeakHashMap<>();
    private CabinNavigation() { }

    static void stop(Mob body) {
        ATTEMPTS.remove(body);
        body.getNavigation().stop();
        body.getMoveControl().setWantedPosition(body.getX(), body.getY(), body.getZ(), 0);
    }

    static void walk(ServerLevel level, AbstractContraptionEntity vehicle, Mob body, Set<BlockPos> goals) {
        if (goals.isEmpty()) return;
        long now = level.getGameTime();
        Attempt previous = ATTEMPTS.get(body);
        if (previous != null && previous.reachable() && previous.goals().equals(goals)
                && goals.contains(body.blockPosition()) && body.onGround()) {
            // Navigation accepts a waypoint before the whole body clears an entrance. Finish the
            // already path-checked final cell by walking to its centre with the native move control.
            Vec3 centre = Vec3.atBottomCenterOf(body.blockPosition());
            body.getNavigation().stop();
            body.getMoveControl().setWantedPosition(centre.x, centre.y, centre.z, 1.0D);
            return;
        }
        if (previous != null && previous.goals().equals(goals) && now - previous.at() < 10) return;
        BlockPos start = body.blockPosition();
        BlockPos end = goals.stream().min(java.util.Comparator.comparingDouble(pos -> pos.distSqr(start))).orElseThrow();
        if (end.distSqr(start) > 16 * 16) return;
        BlockPos min = new BlockPos(Math.min(start.getX(), end.getX()) - 4,
            Math.min(start.getY(), end.getY()) - 3, Math.min(start.getZ(), end.getZ()) - 4);
        BlockPos max = new BlockPos(Math.max(start.getX(), end.getX()) + 4,
            Math.max(start.getY(), end.getY()) + 4, Math.max(start.getZ(), end.getZ()) + 4);
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        vehicle.getContraption().getBlocks().forEach((local, info) -> {
            Vec3 point = vehicle.toGlobalVector(Vec3.atLowerCornerOf(local), 1.0F);
            blocks.put(BlockPos.containing(point.add(0.001D, 0.001D, 0.001D)), info.state());
        });
        // Another body is real traffic: don't plan a path through its standing cell.
        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, AABB.encapsulatingFullBlocks(min, max))) {
            if (other == body || !other.isAlive() || other.isPassenger()) continue;
            AABB box = other.getBoundingBox().inflate(body.getBbWidth() / 2.0D, 0, body.getBbWidth() / 2.0D);
            for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(net.minecraft.util.Mth.ceil(box.minX - 0.5D),
                        net.minecraft.util.Mth.floor(box.minY), net.minecraft.util.Mth.ceil(box.minZ - 0.5D)),
                    new BlockPos(net.minecraft.util.Mth.floor(box.maxX - 0.5D),
                        net.minecraft.util.Mth.floor(box.maxY - 0.001D), net.minecraft.util.Mth.floor(box.maxZ - 0.5D)))) {
                if (!pos.equals(start)) blocks.put(pos.immutable(), Blocks.BARRIER.defaultBlockState());
            }
        }
        var region = new ObstructedPathRegion(level, start, Structures.region(level, min, max), blocks);
        var evaluator = new ResidentNodeEvaluator(start);
        evaluator.setCanOpenDoors(true);
        evaluator.setCanPassDoors(true);
        evaluator.setCanFloat(false);
        var path = new PathFinder(evaluator, 2048).findPath(region, body, goals, 20.0F, 0, 1.0F);
        boolean reachable = path != null && path.canReach();
        ATTEMPTS.put(body, new Attempt(now, Set.copyOf(goals), reachable));
        if (reachable) body.getNavigation().moveTo(path, 1.0D);
        else body.getNavigation().stop();
    }
}
