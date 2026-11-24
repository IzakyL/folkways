package io.github.izakyl.folkways.solve;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Serial inverse kinematics in a shared reference frame. Values are relative to the reference pose. */
public final class Chains {

    private Chains() {
    }

    public record Vec(double x, double y, double z) {
        public Vec {
            finite(x);
            finite(y);
            finite(z);
        }

        public Vec add(Vec v) {
            return new Vec(x + v.x, y + v.y, z + v.z);
        }

        public Vec subtract(Vec v) {
            return new Vec(x - v.x, y - v.y, z - v.z);
        }

        public Vec scale(double s) {
            return new Vec(x * s, y * s, z * s);
        }

        public double dot(Vec v) {
            return x * v.x + y * v.y + z * v.z;
        }

        public Vec cross(Vec v) {
            return new Vec(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x);
        }

        public double length() {
            return Math.hypot(Math.hypot(x, y), z);
        }

        private Vec unit() {
            double largest = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
            if (largest == 0) {
                throw new IllegalArgumentException("Expected a non-zero axis");
            }
            Vec scaled = new Vec(x / largest, y / largest, z / largest);
            return scaled.scale(1 / scaled.length());
        }
    }

    private static final Vec X = new Vec(1, 0, 0);
    private static final Vec ZERO = new Vec(0, 0, 0);

    public sealed interface Joint permits Revolute, Prismatic {
        Vec axis();

        /** Null denotes unlimited travel. */
        Kinematics.Range limits();

        default double clamp(double value) {
            return limits() == null ? value : Math.clamp(value, limits().min(), limits().max());
        }
    }

    /** Angle and limits are in radians about the right-handed reference axis. */
    public record Revolute(Vec pivot, Vec axis, Kinematics.Range limits) implements Joint {
        public Revolute {
            required(pivot);
            axis = required(axis).unit();
        }
    }

    /** Travel and limits are in blocks along the reference axis. */
    public record Prismatic(Vec axis, Kinematics.Range limits) implements Joint {
        public Prismatic {
            axis = required(axis).unit();
        }
    }

    public record Chain(List<Joint> joints, Vec tip, Vec tipAxis) {
        public Chain {
            required(joints);
            joints.forEach(Chains::required);
            joints = List.copyOf(joints);
            required(tip);
            tipAxis = required(tipAxis).unit();
        }

        public Chain(List<Joint> joints, Vec tip) {
            this(joints, tip, X);
        }
    }

    /** Tolerance applies to position distance and, if requested, the distance between unit directions. */
    public record Options(int iterations, double tolerance, Vec direction, double directionWeight) {
        public static final Options DEFAULT = new Options(144, 1e-4, null, 1);

        public Options {
            finite(tolerance);
            finite(directionWeight);
            if (iterations < 1 || iterations > 1024 || tolerance <= 0 || directionWeight <= 0) {
                throw new IllegalArgumentException("Invalid solve options");
            }
            if (direction != null) {
                direction = direction.unit();
            }
        }
    }

    public record Pose(Vec tip, Vec direction) {
    }

    /** {@code iterations} counts the refinement steps taken, for callers that budget the work. */
    private static final int STALL = 60;

    public record Solution(List<Double> values, boolean reached, double residual, int iterations) {
    }

    public record Sample(double control, Vec position) {
        public Sample {
            finite(control);
            required(position);
        }
    }

    /**
     * Conversion is value = (control - zero) * perUnit. The lowest sampled control defines the reference
     * pose since a sweep alone cannot identify an absolute angle or travel origin. Residual is RMS distance.
     */
    public record Fit(Joint joint, double perUnit, double zero, Vec reference, double residual) {
    }

    /** Evaluates the supplied values without clamping. */
    public static Pose forward(Chain chain, List<Double> values) {
        return evaluate(chain, values(chain, values), null);
    }

    private static double[] values(Chain chain, List<Double> values) {
        required(chain);
        required(values);
        if (values.size() != chain.joints.size()) {
            throw new IllegalArgumentException("Expected one value per joint");
        }
        return values.stream().mapToDouble(value -> finite(required(value))).toArray();
    }

    // Apply reference-frame transforms tip to base. A derivative from a downstream joint
    // moves through each upstream rotation as a vector.
    private static Pose evaluate(Chain chain, double[] values, Vec[][] derivatives) {
        Vec tip = chain.tip;
        Vec direction = chain.tipAxis;
        for (int i = values.length - 1; i >= 0; i--) {
            Joint joint = chain.joints.get(i);
            if (joint instanceof Revolute rotation) {
                Vec axis = rotation.axis;
                double sin = Math.sin(values[i]);
                double cos = Math.cos(values[i]);
                Vec arm = rotate(tip.subtract(rotation.pivot), axis, sin, cos);
                direction = rotate(direction, axis, sin, cos);
                tip = rotation.pivot.add(arm);
                if (derivatives != null) {
                    derivatives[i][0] = axis.cross(arm);
                    derivatives[i][1] = axis.cross(direction);
                    for (int j = i + 1; j < values.length; j++) {
                        derivatives[j][0] = rotate(derivatives[j][0], axis, sin, cos);
                        derivatives[j][1] = rotate(derivatives[j][1], axis, sin, cos);
                    }
                }
            } else {
                tip = tip.add(joint.axis().scale(values[i]));
                if (derivatives != null) {
                    derivatives[i][0] = joint.axis();
                    derivatives[i][1] = ZERO;
                }
            }
        }
        return new Pose(tip, direction);
    }

    private static Vec rotate(Vec vector, Vec axis, double sin, double cos) {
        return vector.scale(cos).add(axis.cross(vector).scale(sin))
            .add(axis.scale(axis.dot(vector) * (1 - cos)));
    }

    /** Solves with damped least squares and returns the best pose found within the iteration budget. */
    public static Solution solve(Chain chain, Vec target, List<Double> start, Options options) {
        required(chain);
        required(target);
        required(options);
        int count = chain.joints.size();
        double[] values = start == null ? new double[count] : values(chain, start);
        for (int i = 0; i < count; i++) {
            values[i] = chain.joints.get(i).clamp(values[i]);
        }
        double[] best = values.clone();
        Pose bestPose = evaluate(chain, best, null);
        double bestScore = norm(error(bestPose, target, options));
        double damping = 0.05;
        var random = new Random(0);
        int iteration = 0;
        int stalled = 0;
        // A target out of reach is never met: stop once a long run of steps and restarts brings the best no closer.
        for (; count > 0 && iteration < options.iterations && !reached(bestPose, target, options) && stalled < STALL;
                iteration++, stalled++) {
            if (damping > 100 || (iteration > 0 && iteration % 48 == 0)) {
                values = best.clone();
                for (int i = 0; i < count; i++) {
                    values[i] = chain.joints.get(i).clamp(values[i] + random.nextDouble(-1, 1));
                }
                damping = 0.05;
            }
            var derivatives = new Vec[count][2];
            Pose pose = evaluate(chain, values, derivatives);
            double[] error = error(pose, target, options);
            double score = norm(error);
            if (score < bestScore - 1e-9) {
                stalled = 0;
            }
            if (score < bestScore || reached(pose, target, options)) {
                best = values;
                bestPose = pose;
                bestScore = score;
                if (reached(bestPose, target, options)) {
                    break;
                }
            }
            var columns = new double[count][];
            var system = new double[error.length][error.length];
            for (int i = 0; i < error.length; i++) {
                system[i][i] = damping * damping;
            }
            for (int i = 0; i < count; i++) {
                Joint joint = chain.joints.get(i);
                double[] column = components(derivatives[i][0], derivatives[i][1], options);
                double descent = dot(column, error);
                if (joint.limits() != null && (joint.limits().min() == joint.limits().max()
                    || (values[i] <= joint.limits().min() && descent <= 0)
                    || (values[i] >= joint.limits().max() && descent >= 0))) {
                    continue;
                }
                columns[i] = column;
                for (int row = 0; row < error.length; row++) {
                    for (int col = 0; col < error.length; col++) {
                        system[row][col] += column[row] * column[col];
                    }
                }
            }
            double[] correction = linearSolve(system, error);
            if (correction == null) {
                damping *= 4;
                continue;
            }
            double[] candidate = values.clone();
            for (int i = 0; i < count; i++) {
                if (columns[i] != null) {
                    double step = dot(columns[i], correction);
                    if (chain.joints.get(i) instanceof Revolute) {
                        step = Math.clamp(step, -1, 1);
                    }
                    candidate[i] = chain.joints.get(i).clamp(values[i] + step);
                }
            }
            Pose candidatePose = evaluate(chain, candidate, null);
            double candidateScore = norm(error(candidatePose, target, options));
            if (candidateScore < score) {
                values = candidate;
                damping = Math.max(1e-5, damping * 0.5);
            } else {
                damping *= 4;
            }
            if (candidateScore < bestScore - 1e-9 || reached(candidatePose, target, options)) {
                stalled = 0;
            }
            if (candidateScore < bestScore || reached(candidatePose, target, options)) {
                best = candidate;
                bestPose = candidatePose;
                bestScore = candidateScore;
            }
        }
        return new Solution(Arrays.stream(best).boxed().toList(), reached(bestPose, target, options),
            target.subtract(bestPose.tip).length(), iteration);
    }

    private static boolean reached(Pose pose, Vec target, Options options) {
        return target.subtract(pose.tip).length() <= options.tolerance
            && (options.direction == null || options.direction.subtract(pose.direction).length() <= options.tolerance);
    }

    private static double[] error(Pose pose, Vec target, Options options) {
        Vec directionError = options.direction == null ? ZERO : options.direction.subtract(pose.direction);
        return components(target.subtract(pose.tip), directionError, options);
    }

    private static double[] components(Vec position, Vec direction, Options options) {
        if (options.direction == null) {
            return new double[] {position.x, position.y, position.z};
        }
        Vec weighted = direction.scale(options.directionWeight);
        return new double[] {position.x, position.y, position.z, weighted.x, weighted.y, weighted.z};
    }

    /** Fits a line by regressing the observed positions on their control values. */
    public static Fit fitPrismatic(List<Sample> samples) {
        var data = new Sweep(samples, 2);
        Vec slope = ZERO;
        for (Sample sample : data.samples) {
            slope = slope.add(sample.position.subtract(data.center).scale(data.controlDelta(sample)));
        }
        slope = slope.scale(1 / data.controlVariance);
        double speed = finite(slope.length());
        Vec direction = slope.unit();
        Vec reference = data.center.subtract(slope.scale(data.meanControl));
        double residual = 0;
        for (Sample sample : data.samples) {
            Vec predicted = reference.add(slope.scale(sample.control - data.zero));
            residual = Math.hypot(residual, predicted.subtract(sample.position).length());
        }
        return new Fit(new Prismatic(direction, null), speed, data.zero, reference,
            residual / Math.sqrt(data.samples.size()));
    }

    /**
     * Fits a plane, a circle within that plane, and a linear angle sweep. Probe densely enough that
     * successive distinct control values rotate by less than half a turn so phase can be unwrapped.
     */
    public static Fit fitRevolute(List<Sample> samples) {
        var data = new Sweep(samples, 3);
        Vec axis = planeNormal(data);
        Vec u = data.samples.stream().map(sample -> sample.position.subtract(data.center))
            .map(v -> v.subtract(axis.scale(v.dot(axis))))
            .max(Comparator.comparingDouble(Vec::length)).orElseThrow().unit();
        Vec v = axis.cross(u);
        double scale = data.samples.stream().mapToDouble(sample -> sample.position.subtract(data.center).length())
            .max().orElseThrow();
        var matrix = new double[3][3];
        var rhs = new double[3];
        for (Sample sample : data.samples) {
            Vec point = sample.position.subtract(data.center);
            double x = point.dot(u) / scale;
            double y = point.dot(v) / scale;
            double[] row = {2 * x, 2 * y, 1};
            double squared = x * x + y * y;
            for (int i = 0; i < row.length; i++) {
                rhs[i] += row[i] * squared;
                for (int j = 0; j < row.length; j++) {
                    matrix[i][j] += row[i] * row[j];
                }
            }
        }
        double[] circle = linearSolve(matrix, rhs);
        if (circle == null) {
            throw new IllegalArgumentException("Samples do not define a circle");
        }
        Vec pivot = data.center.add(u.scale(circle[0] * scale)).add(v.scale(circle[1] * scale));
        double radius = Math.sqrt(circle[2] + circle[0] * circle[0] + circle[1] * circle[1]) * scale;
        double previous = 0;
        double phase = 0;
        double meanPhase = 0;
        double slope = 0;
        for (int i = 0; i < data.samples.size(); i++) {
            Sample sample = data.samples.get(i);
            Vec arm = sample.position.subtract(pivot);
            double angle = Math.atan2(arm.dot(v), arm.dot(u));
            phase = i == 0 ? angle : phase + Math.IEEEremainder(angle - previous, 2 * Math.PI);
            previous = angle;
            slope += data.controlDelta(sample) * phase;
            meanPhase += phase / data.samples.size();
        }
        double speed = finite(slope / data.controlVariance);
        if (speed == 0) {
            throw new IllegalArgumentException("Samples do not define angular motion");
        }
        double phaseAtZero = meanPhase - speed * data.meanControl;
        Vec arm = u.scale(radius * Math.cos(phaseAtZero)).add(v.scale(radius * Math.sin(phaseAtZero)));
        double residual = 0;
        for (Sample sample : data.samples) {
            double angle = (sample.control - data.zero) * speed;
            Vec predicted = pivot.add(rotate(arm, axis, Math.sin(angle), Math.cos(angle)));
            residual = Math.hypot(residual, predicted.subtract(sample.position).length());
        }
        return new Fit(new Revolute(pivot, axis, null), speed, data.zero, pivot.add(arm),
            residual / Math.sqrt(data.samples.size()));
    }

    private static Vec planeNormal(Sweep data) {
        double scale = data.samples.stream().mapToDouble(sample -> sample.position.subtract(data.center).length())
            .max().orElseThrow();
        if (scale == 0) {
            throw new IllegalArgumentException("Samples do not define a plane");
        }
        Vec x = ZERO;
        Vec y = ZERO;
        Vec z = ZERO;
        for (Sample sample : data.samples) {
            Vec point = sample.position.subtract(data.center);
            Vec p = new Vec(point.x / scale, point.y / scale, point.z / scale);
            x = x.add(p.scale(p.x));
            y = y.add(p.scale(p.y));
            z = z.add(p.scale(p.z));
        }
        Vec normal = List.of(x.cross(y), y.cross(z), z.cross(x)).stream()
            .max(Comparator.comparingDouble(Vec::length)).orElseThrow();
        if (normal.length() <= 1e-12 * (x.x + y.y + z.z) * (x.x + y.y + z.z)) {
            throw new IllegalArgumentException("Samples do not define a plane");
        }
        normal = normal.unit();
        // Inverse iteration on the regularized covariance finds the least-variance axis.
        double ridge = (x.x + y.y + z.z) * 1e-8;
        for (int i = 0; i < 12; i++) {
            double[] next = linearSolve(new double[][] {
                {x.x + ridge, x.y, x.z}, {y.x, y.y + ridge, y.z}, {z.x, z.y, z.z + ridge}
            }, new double[] {normal.x, normal.y, normal.z});
            if (next == null) {
                break;
            }
            normal = new Vec(next[0], next[1], next[2]).unit();
        }
        return normal;
    }

    private static final class Sweep {
        private final List<Sample> samples;
        private final double zero;
        private final double meanControl;
        private final Vec center;
        private final double controlVariance;

        private Sweep(List<Sample> samples, int minimum) {
            if (required(samples).size() < minimum) {
                throw new IllegalArgumentException("Not enough calibration samples");
            }
            this.samples = samples.stream().map(Chains::required).sorted(Comparator.comparingDouble(Sample::control))
                .toList();
            zero = this.samples.getFirst().control;
            meanControl = finite(this.samples.stream().mapToDouble(sample -> sample.control - zero).average()
                .orElseThrow());
            Vec mean = ZERO;
            for (Sample sample : this.samples) {
                mean = mean.add(sample.position.scale(1.0 / samples.size()));
            }
            center = mean;
            controlVariance = finite(this.samples.stream().mapToDouble(sample -> {
                double delta = controlDelta(sample);
                return delta * delta;
            }).sum());
            if (controlVariance == 0) {
                throw new IllegalArgumentException("Calibration needs distinct controls");
            }
        }

        private double controlDelta(Sample sample) {
            return (sample.control - zero) - meanControl;
        }
    }

    private static double norm(double[] vector) {
        double length = 0;
        for (double component : vector) {
            length = Math.hypot(length, component);
        }
        return length;
    }

    private static double dot(double[] a, double[] b) {
        double result = 0;
        for (int i = 0; i < a.length; i++) {
            result += a[i] * b[i];
        }
        return result;
    }

    private static double[] linearSolve(double[][] matrix, double[] rhs) {
        rhs = rhs.clone();
        for (int i = 0; i < rhs.length; i++) {
            int pivot = i;
            for (int j = i + 1; j < rhs.length; j++) {
                if (Math.abs(matrix[j][i]) > Math.abs(matrix[pivot][i])) {
                    pivot = j;
                }
            }
            if (!Double.isFinite(matrix[pivot][i]) || Math.abs(matrix[pivot][i]) < 1e-14) {
                return null;
            }
            double[] row = matrix[i];
            matrix[i] = matrix[pivot];
            matrix[pivot] = row;
            double value = rhs[i];
            rhs[i] = rhs[pivot];
            rhs[pivot] = value;
            for (int j = i + 1; j < rhs.length; j++) {
                double factor = matrix[j][i] / matrix[i][i];
                for (int k = i + 1; k < rhs.length; k++) {
                    matrix[j][k] -= factor * matrix[i][k];
                }
                rhs[j] -= factor * rhs[i];
            }
        }
        for (int i = rhs.length - 1; i >= 0; i--) {
            for (int j = i + 1; j < rhs.length; j++) {
                rhs[i] -= matrix[i][j] * rhs[j];
            }
            rhs[i] /= matrix[i][i];
        }
        return rhs;
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Expected a finite number");
        }
        return value;
    }

    private static <T> T required(T value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing required value");
        }
        return value;
    }
}
