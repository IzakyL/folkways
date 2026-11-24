package io.github.izakyl.folkways.solve;

/** Independent actuator travel along three axes in the same frame as the target. */
public final class Kinematics {

    private Kinematics() {
    }

    public record Range(double min, double max) {
        public Range {
            if (!Double.isFinite(min) || !Double.isFinite(max) || min > max) {
                throw new IllegalArgumentException("Invalid axis range");
            }
        }

        private double clamp(double value) {
            return Math.clamp(value, min, max);
        }
    }

    public record Target(double x, double y, double z, boolean reachable) {
    }

    /** An actuator's zero position, signed displacement per unit of travel, and travel limits. */
    public record Axis(double origin, double displacement, Range travel) {
        public Axis {
            if (!Double.isFinite(origin) || !Double.isFinite(displacement) || displacement == 0) {
                throw new IllegalArgumentException("Invalid axis calibration");
            }
            if (!Double.isFinite(origin + travel.min() * displacement)
                || !Double.isFinite(origin + travel.max() * displacement)) {
                throw new IllegalArgumentException("Invalid axis endpoints");
            }
        }

        public double coordinate(double extension) {
            return origin + extension * displacement;
        }

        private double extension(double coordinate) {
            return (coordinate - origin) / displacement;
        }
    }

    /** Computes actuator travel from a target measured in the actuators' coordinate frame. */
    public static Target solve(Axis x, Axis y, Axis z, double targetX, double targetY, double targetZ) {
        if (!Double.isFinite(targetX) || !Double.isFinite(targetY) || !Double.isFinite(targetZ)) {
            throw new IllegalArgumentException("Expected finite coordinates");
        }
        double extensionX = x.extension(targetX);
        double extensionY = y.extension(targetY);
        double extensionZ = z.extension(targetZ);
        double clippedX = x.travel().clamp(extensionX);
        double clippedY = y.travel().clamp(extensionY);
        double clippedZ = z.travel().clamp(extensionZ);
        return new Target(clippedX, clippedY, clippedZ, extensionX == clippedX && extensionY == clippedY
            && extensionZ == clippedZ);
    }

    public static Target solve(Range x, Range y, Range z, double targetX, double targetY, double targetZ) {
        if (!Double.isFinite(targetX) || !Double.isFinite(targetY) || !Double.isFinite(targetZ)) {
            throw new IllegalArgumentException("Expected finite coordinates");
        }
        double clippedX = x.clamp(targetX);
        double clippedY = y.clamp(targetY);
        double clippedZ = z.clamp(targetZ);
        return new Target(clippedX, clippedY, clippedZ, clippedX == targetX && clippedY == targetY
            && clippedZ == targetZ);
    }
}
