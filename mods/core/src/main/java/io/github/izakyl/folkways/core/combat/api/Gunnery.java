package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.solve.Ballistics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Laying a gun from what its aim part reports: where it pivots, how far out the muzzle is, how its mount turns against
 * the world, and the limits of its aim. The same arithmetic serves scripts on a worker thread and fire control on the
 * server thread, so neither touches the world here.
 */
public final class Gunnery {

    private Gunnery() {
    }

    /**
     * A gun's geometry: the pivot in world coordinates, the muzzle's distance along the bore, the world directions of
     * the mount's own axes, the offset from the part's yaw to the mount's, whether the part's pitch rises upward, and
     * the part's limits per axis.
     */
    public record Weapon(Vec3 pivot, double muzzle, Vec3 x, Vec3 y, Vec3 z, double yawOffset, boolean pitchUp,
                         double[][] limits) {

        /** Reads the fields of an aim part's {@code ballistics} reading, as plain maps, lists and numbers. */
        public static Weapon of(Map<?, ?> data) {
            Vec3 pivot = vector(data.get("pivot"), "pivot");
            double muzzle = data.containsKey("muzzle") ? number(data.get("muzzle"), "muzzle") : 0;
            Vec3 x = new Vec3(1, 0, 0);
            Vec3 y = new Vec3(0, 1, 0);
            Vec3 z = new Vec3(0, 0, 1);
            if (data.get("frame") instanceof Map<?, ?> frame) {
                x = vector(frame.get("x"), "frame.x");
                y = vector(frame.get("y"), "frame.y");
                z = vector(frame.get("z"), "frame.z");
            }
            double yawOffset = data.containsKey("yaw_offset") ? number(data.get("yaw_offset"), "yaw_offset") : 0;
            boolean pitchUp = !(data.get("pitch_up") instanceof Boolean up) || up;
            double[][] limits = {{-360, 360}, {-90, 90}};
            if (data.get("limits") instanceof List<?> axes && axes.size() == 2) {
                for (int i = 0; i < 2; i++) {
                    if (!(axes.get(i) instanceof List<?> range) || range.size() != 2) {
                        throw new IllegalArgumentException("limits must hold [min, max] for yaw and pitch");
                    }
                    limits[i] = new double[] {number(range.get(0), "limits"), number(range.get(1), "limits")};
                }
            }
            return new Weapon(pivot, muzzle, x, y, z, yawOffset, pitchUp, limits);
        }

        Vec3 toWorld(Vec3 local) {
            return x.scale(local.x).add(y.scale(local.y)).add(z.scale(local.z));
        }

        Vec3 toLocal(Vec3 world) {
            return new Vec3(world.dot(x), world.dot(y), world.dot(z));
        }

        /** The world direction of the bore for the part's angles. */
        public Vec3 direction(double yaw, double pitch) {
            double rise = Mth.clamp(pitchUp ? pitch : -pitch, -90, 90);
            return toWorld(Vec3.directionFromRotation((float) -rise, (float) (yaw + yawOffset))).normalize();
        }

        /** The part's angles that lay the bore along a world direction, or empty beyond the part's limits. */
        public Optional<double[]> angles(Vec3 direction) {
            Vec3 local = toLocal(direction.normalize());
            double yaw = Math.toDegrees(Math.atan2(-local.x, local.z));
            double pitch = Math.toDegrees(Math.atan2(local.y, Math.hypot(local.x, local.z)));
            // Written as floats by the native mounts, so read back exactly as written.
            double partYaw = (float) Mth.wrapDegrees(yaw - yawOffset);
            double partPitch = (float) (pitchUp ? pitch : -pitch);
            if (partYaw < limits[0][0] || partYaw > limits[0][1]
                    || partPitch < limits[1][0] || partPitch > limits[1][1]) {
                return Optional.empty();
            }
            return Optional.of(new double[] {partYaw, partPitch});
        }

        public Trajectories.Launch launch(Ballistics.Model model, double yaw, double pitch) {
            Vec3 direction = direction(yaw, pitch);
            return new Trajectories.Launch(pivot.add(direction.scale(muzzle)), world(direction), model);
        }
    }

    public static Ballistics.Model model(Map<?, ?> data) {
        return new Ballistics.Model(number(data.get("speed"), "speed"), number(data.get("gravity"), "gravity"),
            data.containsKey("retention") ? number(data.get("retention"), "retention") : 1);
    }

    /**
     * The part's angles that land a shot on the target, the flatter arc first unless {@code indirect}, allowing for the
     * muzzle's stand-off from the pivot; empty when no arc within the part's limits reaches it.
     */
    public static Optional<double[]> aim(Weapon weapon, Ballistics.Model model, Vec3 target, boolean indirect) {
        Vec3 offset = target.subtract(weapon.pivot());
        List<Ballistics.Shot> shots = Ballistics.solve(model, point(offset));
        for (Ballistics.Shot shot : indirect ? shots.reversed() : shots) {
            boolean low = shot == shots.getFirst();
            Ballistics.Aim angle = shot.aim();
            for (int i = 0; i < 5 && weapon.muzzle() != 0; i++) {
                Vec3 fromMuzzle = offset.subtract(Vec3.directionFromRotation((float) -angle.pitch(),
                    (float) angle.yaw()).scale(weapon.muzzle()));
                var adjusted = Ballistics.solve(model, point(fromMuzzle));
                if (adjusted.isEmpty()) {
                    break;
                }
                angle = (low ? adjusted.getFirst() : adjusted.getLast()).aim();
            }
            var angles = weapon.angles(Vec3.directionFromRotation((float) -angle.pitch(), (float) angle.yaw()));
            if (angles.isPresent()) {
                return angles;
            }
        }
        return Optional.empty();
    }

    /** A flight sampled tick by tick in free space, as {@link Trajectories#trace} steps it without the world. */
    public record Flight(List<Vec3> points, boolean arrives) {
    }

    /**
     * Samples the flight until it reaches the target's nearest approach, falls far below where it left, or runs out of
     * the simulated horizon.
     */
    public static Flight flight(Trajectories.Launch launch, Vec3 target, double floor) {
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
            distance += velocity;
            drop += fall;
            velocity *= model.retention();
            fall = fall * model.retention() + model.gravity();
            Vec3 next = launch.muzzle().add(direction.scale(model.speed() * distance)).subtract(0, drop, 0);
            if (target != null && next.distanceToSqr(target) > previous.distanceToSqr(target)
                    && previous.distanceToSqr(target) <= previous.distanceToSqr(next)) {
                path.add(target);
                return new Flight(path, true);
            }
            path.add(next);
            if (next.y < floor) {
                break;
            }
            previous = next;
        }
        return new Flight(path, false);
    }

    static Ballistics.Aim world(Vec3 direction) {
        return new Ballistics.Aim(Math.toDegrees(Math.atan2(-direction.x, direction.z)),
            Mth.clamp(Math.toDegrees(Math.atan2(direction.y, direction.horizontalDistance())), -90, 90));
    }

    private static Ballistics.Point point(Vec3 at) {
        return new Ballistics.Point(at.x, at.y, at.z);
    }

    public static double number(Object value, String name) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof net.starlark.java.eval.StarlarkFloat real) {
            return real.toDouble();
        }
        if (value instanceof net.starlark.java.eval.StarlarkInt whole) {
            return whole.toDouble();
        }
        throw new IllegalArgumentException(name + " must be a number");
    }

    public static Vec3 vector(Object value, String name) {
        if (value instanceof List<?> xyz && xyz.size() == 3) {
            return new Vec3(number(xyz.get(0), name), number(xyz.get(1), name), number(xyz.get(2), name));
        }
        throw new IllegalArgumentException(name + " must be [x, y, z]");
    }
}
