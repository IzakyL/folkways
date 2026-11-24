package io.github.izakyl.folkways.solve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Constant-coefficient flight in free space, with linear motion within each tick. */
public final class Ballistics {

    public static final int MAX_TICKS = 1200;
    private static final int BISECTIONS = 48;
    private static final double ANGLE_EPSILON = 1e-9;

    private Ballistics() {
    }

    /** Speed in blocks/tick, downward gravity in blocks/tick², and velocity retained per tick in [0, 1]. */
    public record Model(double speed, double gravity, double retention) {
        public Model {
            if (!Double.isFinite(speed) || speed <= 0 || !Double.isFinite(gravity) || gravity < 0
                || !(retention >= 0 && retention <= 1)) {
                throw new IllegalArgumentException("Invalid flight model");
            }
        }
    }

    public record Point(double x, double y, double z) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Expected finite coordinates");
            }
        }
    }

    /** Angles in degrees. Yaw zero faces +Z and positive yaw turns toward -X. Positive pitch rises. */
    public record Aim(double yaw, double pitch) {
        public Aim {
            if (!Double.isFinite(yaw) || !(pitch >= -90 && pitch <= 90)) {
                throw new IllegalArgumentException("Invalid aim");
            }
        }
    }

    public record Shot(Aim aim, double ticks) {
    }

    public record Landing(Point point, double ticks) {
    }

    /**
     * Returns the lowest and highest distinct elevations reachable within MAX_TICKS, ordered by pitch.
     * A single elevation uses its earliest arrival. A target at the origin is reached at tick zero.
     * Each crossing is bracketed to at most 2^-48 tick (subject to double precision).
     * Elevations within 1e-9 degree are treated as a single direction.
     */
    public static List<Shot> solve(Model model, Point target) {
        double range = Math.hypot(target.x(), target.z());
        double yaw = range == 0 ? 0 : Math.toDegrees(Math.atan2(-target.x(), target.z()));
        if (range == 0 && target.y() == 0) {
            return List.of(new Shot(new Aim(yaw, 0), 0));
        }

        List<Shot> hits = new ArrayList<>();
        Flight flight = new Flight(model);
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            double horizontal = model.speed() * flight.distance;
            double vertical = target.y() + flight.drop;
            double dh = model.speed() * flight.velocity;
            double dv = flight.fall;
            // A hit requires hypot(range, vertical + f*dv) = horizontal + f*dh.
            // Squaring gives a quadratic. Split at its vertex so each interval has at most one crossing.
            double quadratic = dv * dv - dh * dh;
            double vertex = (horizontal * dh - vertical * dv) / quadratic;
            if (vertex > 0 && vertex < 1) {
                findHit(hits, tick, yaw, range, horizontal, vertical, dh, dv, 0, vertex);
                findHit(hits, tick, yaw, range, horizontal, vertical, dh, dv, vertex, 1);
            } else {
                findHit(hits, tick, yaw, range, horizontal, vertical, dh, dv, 0, 1);
            }
            flight.advance();
        }

        hits.sort(Comparator.comparingDouble(shot -> shot.aim().pitch()));
        if (hits.isEmpty()) {
            return List.of();
        }
        Shot low = hits.getFirst();
        Shot high = hits.getLast();
        if (high.aim().pitch() - low.aim().pitch() <= ANGLE_EPSILON) {
            return List.of(hits.stream().min(Comparator.comparingDouble(Shot::ticks)).orElseThrow());
        }
        return List.of(low, high);
    }

    private static void findHit(List<Shot> hits, int tick, double yaw, double range, double horizontal,
                                double vertical, double dh, double dv, double lo, double hi) {
        double left = residual(range, horizontal, vertical, dh, dv, lo);
        double right = residual(range, horizontal, vertical, dh, dv, hi);
        if (left == 0) {
            hi = lo;
        } else if (right == 0) {
            lo = hi;
        } else if ((left < 0) == (right < 0)) {
            return;
        }
        for (int i = 0; i < BISECTIONS && lo < hi; i++) {
            double mid = (lo + hi) / 2;
            double error = residual(range, horizontal, vertical, dh, dv, mid);
            if ((error < 0) == (left < 0)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double fraction = (lo + hi) / 2;
        double pitch = Math.toDegrees(Math.atan2(Math.fma(fraction, dv, vertical), range));
        hits.add(new Shot(new Aim(yaw, pitch), tick + fraction));
    }

    private static double residual(double range, double horizontal, double vertical, double dh, double dv,
                                   double fraction) {
        return Math.hypot(range, Math.fma(fraction, dv, vertical)) - Math.fma(fraction, dh, horizontal);
    }

    /** Relative position along a free-flight trajectory at a specified (possibly fractional) tick. */
    public static Point position(Model model, Aim aim, double ticks) {
        if (!(ticks >= 0 && ticks <= MAX_TICKS)) {
            throw new IllegalArgumentException("Time outside simulation horizon");
        }
        Flight flight = new Flight(model);
        int whole = (int) ticks;
        for (int i = 0; i < whole; i++) {
            flight.advance();
        }
        return point(model, aim, flight, ticks - whole);
    }

    /** First descending crossing of the plane at relative height, within MAX_TICKS. */
    public static Optional<Landing> land(Model model, Aim aim, double height) {
        if (!Double.isFinite(height)) {
            throw new IllegalArgumentException("Expected finite height");
        }
        double verticalSpeed = model.speed() * Math.sin(Math.toRadians(aim.pitch()));
        Flight flight = new Flight(model);
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            double y = verticalSpeed * flight.distance - flight.drop;
            double dy = verticalSpeed * flight.velocity - flight.fall;
            if (dy < 0 && y >= height && y + dy <= height) {
                double fraction = (height - y) / dy;
                Point at = point(model, aim, flight, fraction);
                return Optional.of(new Landing(new Point(at.x(), height, at.z()), tick + fraction));
            }
            flight.advance();
        }
        return Optional.empty();
    }

    private static Point point(Model model, Aim aim, Flight flight, double fraction) {
        double pitch = Math.toRadians(aim.pitch());
        double yaw = Math.toRadians(aim.yaw());
        double distance = model.speed() * Math.fma(fraction, flight.velocity, flight.distance);
        double range = Math.abs(aim.pitch()) == 90 ? 0 : distance * Math.cos(pitch);
        double height = distance * Math.sin(pitch) - Math.fma(fraction, flight.fall, flight.drop);
        return new Point(-range * Math.sin(yaw), height, range * Math.cos(yaw));
    }

    // Position = initial velocity * distance - (0, drop, 0).
    // FastThrowableProjectile.projectileMove and AbstractArrow.tick both move with the old velocity.
    static final class Flight {
        private final Model model;
        double velocity = 1;
        double fall;
        double distance;
        double drop;

        Flight(Model model) {
            this.model = model;
        }

        void advance() {
            distance += velocity;
            drop += fall;
            velocity *= model.retention();
            fall = fall * model.retention() + model.gravity();
        }
    }
}
