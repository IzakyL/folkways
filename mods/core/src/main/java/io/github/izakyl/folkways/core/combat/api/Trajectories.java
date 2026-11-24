package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.solve.Ballistics;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Predicted cannon flights through the loaded world, stepped tick by tick as {@link Ballistics} models them. */
public final class Trajectories {

    /** A shot as it leaves the muzzle: where, in which direction, and how it flies. */
    public record Launch(Vec3 muzzle, Ballistics.Aim aim, Ballistics.Model model) {
    }

    private Trajectories() {
    }

    /**
     * The flight's position at every tick until it strikes a block, leaves the loaded world or the build height, or
     * runs out of the simulated horizon. A strike ends the path at the struck point.
     */
    public static Routine.Path trace(ServerLevel level, Launch launch) {
        return trace(level, launch, null);
    }

    public static Routine.Path trace(ServerLevel level, Launch launch, Vec3 target) {
        Vec3 direction = Vec3.directionFromRotation((float) -launch.aim().pitch(), (float) launch.aim().yaw());
        Ballistics.Model model = launch.model();
        List<Vec3> path = new ArrayList<>();
        path.add(launch.muzzle());
        Vec3 previous = launch.muzzle();
        double velocity = 1;
        double fall = 0;
        double distance = 0;
        double drop = 0;
        for (int tick = 0; tick < Ballistics.MAX_TICKS; tick++) {
            // Moves with the old velocity, then drags and falls, as the projectiles do.
            distance += velocity;
            drop += fall;
            velocity *= model.retention();
            fall = fall * model.retention() + model.gravity();
            Vec3 next = launch.muzzle().add(direction.scale(model.speed() * distance)).subtract(0, drop, 0);
            if (next.y < level.getMinBuildHeight() || next.y > level.getMaxBuildHeight() + 64
                    || !level.hasChunkAt(BlockPos.containing(next))) {
                return new Routine.Path(path, false);
            }
            boolean arrives = target != null && next.distanceToSqr(target) > previous.distanceToSqr(target)
                && previous.distanceToSqr(target) <= previous.distanceToSqr(next);
            if (arrives) {
                next = target;
            }
            var hit = level.clip(new ClipContext(previous, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.MISS) {
                path.add(hit.getLocation());
                return new Routine.Path(path, true);
            }
            path.add(next);
            if (arrives) {
                return new Routine.Path(path, true);
            }
            previous = next;
        }
        return new Routine.Path(path, false);
    }
}
