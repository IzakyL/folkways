package io.github.izakyl.folkways.vanilla.build.draft;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

public sealed interface Solid {

    BoundingBox bounds();

    boolean holds(double x, double y, double z);

    /** 0 means wholly outside, 1 wholly inside, -1 requires sampling. Limits include sample centers. */
    default int coverage(SampleBox box) {
        return -1;
    }

    record SampleBox(double x0, double y0, double z0, double x1, double y1, double z1) {

        SampleBox local(Frame frame) {
            // Opposite corners bound the transformed box only for axis-aligned frames.
            if (!frame.square()) {
                return null;
            }
            Vec3 a = frame.local(x0, y0, z0);
            Vec3 b = frame.local(x1, y1, z1);
            return new SampleBox(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z),
                Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z));
        }

        int within(double lx, double ly, double lz, double hx, double hy, double hz) {
            if (x1 < lx || y1 < ly || z1 < lz || x0 >= hx || y0 >= hy || z0 >= hz) {
                return 0;
            }
            return x0 >= lx && y0 >= ly && z0 >= lz && x1 < hx && y1 < hy && z1 < hz ? 1 : -1;
        }

        int within(Vec3i size) {
            return within(0, 0, 0, size.getX(), size.getY(), size.getZ());
        }

        SampleBox lowered(double low, double high) {
            return new SampleBox(x0, Math.nextDown(y0 - high), z0, x1, Math.nextUp(y1 - low), z1);
        }
    }

    // Extrema of a square over a closed interval; strict margins keep roundoff on the sampling path.
    private static double nearSquare(double low, double high) {
        return low <= 0 && high >= 0 ? 0 : Math.min(low * low, high * high);
    }

    private static double farSquare(double low, double high) {
        return Math.max(low * low, high * high);
    }

    private static int radial(double near, double far, double radius, boolean inside) {
        double margin = 1.0E-10 * Math.max(1, radius);
        if (near > radius + margin) {
            return 0;
        }
        return inside && far < radius - margin ? 1 : -1;
    }

    private static int band(double y0, double y1, double low, double high, double thickness, boolean inside) {
        double margin = 1.0E-10 * Math.max(1, Math.max(Math.abs(low), Math.abs(high)) + thickness);
        if (y1 < low - margin || y0 > high + thickness + margin) {
            return 0;
        }
        return inside && y0 > high + margin && y1 < low + thickness - margin ? 1 : -1;
    }

    record CurvedBeam(Frame frame, Vec3i size, double thickness, double lift) implements Solid {

        public CurvedBeam {
            Objects.requireNonNull(frame, "frame");
            room(size);
            if (!Double.isFinite(thickness + lift) || thickness <= 0 || lift < 0 || thickness + lift > size.getY()) {
                throw new IllegalArgumentException("beam thickness and lift must fit its height");
            }
        }

        @Override
        public BoundingBox bounds() {
            return around(frame, size.getX(), size.getY(), size.getZ());
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 p = frame.local(x, y, z);
            double t = 2 * p.x / size.getX() - 1;
            double floor = lift * t * t;
            return within(p, size) && p.y >= floor && p.y < floor + thickness;
        }

        @Override
        public int coverage(SampleBox box) {
            SampleBox p = box.local(frame);
            if (p == null) {
                return -1;
            }
            int within = p.within(size);
            if (within == 0) {
                return 0;
            }
            double a = 2 * p.x0 / size.getX() - 1;
            double b = 2 * p.x1 / size.getX() - 1;
            return band(p.y0, p.y1, lift * nearSquare(a, b), lift * farSquare(a, b), thickness, within == 1);
        }
    }

    record Frustum(Frame frame, Vec3i size, double bottom, double top, double lean, double height,
            double elevation) implements Solid {

        public Frustum {
            Objects.requireNonNull(frame, "frame");
            room(size);
            if (!Double.isFinite(bottom + top + lean + height + elevation)
                    || elevation < 0 || height <= 0 || height + elevation > size.getY()
                    || bottom <= 0 || top <= 0 || Math.max(bottom, top) + Math.abs(lean) > size.getX() / 2.0
                    || Math.max(bottom, top) > size.getZ() / 2.0) {
                throw new IllegalArgumentException("frustum radii and lean must fit its scope");
            }
        }

        @Override
        public BoundingBox bounds() {
            return around(frame, size.getX(), size.getY(), size.getZ());
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 p = frame.local(x, y, z);
            double t = (p.y - elevation) / height;
            double radius = bottom + (top - bottom) * t;
            double dx = p.x - size.getX() / 2.0 - lean * t;
            double dz = p.z - size.getZ() / 2.0;
            return within(p, size) && p.y >= elevation && p.y < elevation + height
                && dx * dx + dz * dz <= radius * radius;
        }

        @Override
        public int coverage(SampleBox box) {
            SampleBox p = box.local(frame);
            if (p == null) {
                return -1;
            }
            int within = p.within(0, elevation, 0, size.getX(), elevation + height, size.getZ());
            if (within == 0) {
                return 0;
            }
            double a = Math.clamp((p.y0 - elevation) / height, 0, 1);
            double b = Math.clamp((p.y1 - elevation) / height, 0, 1);
            double ra = bottom + (top - bottom) * a;
            double rb = bottom + (top - bottom) * b;
            double x0 = p.x0 - size.getX() / 2.0 - Math.max(lean * a, lean * b);
            double x1 = p.x1 - size.getX() / 2.0 - Math.min(lean * a, lean * b);
            double z0 = p.z0 - size.getZ() / 2.0;
            double z1 = p.z1 - size.getZ() / 2.0;
            // Independent radius and centre bounds enclose even a leaning cone.
            double near = nearSquare(x0, x1) + nearSquare(z0, z1);
            double far = farSquare(x0, x1) + farSquare(z0, z1);
            if (radial(near, far, Math.max(ra * ra, rb * rb), false) == 0) {
                return 0;
            }
            return within == 1 && radial(near, far, Math.min(ra * ra, rb * rb), true) == 1 ? 1 : -1;
        }
    }

    record CurvedHip(Frame frame, Vec3i size, double rise, double upturn, double thickness) implements Solid {

        public CurvedHip {
            Objects.requireNonNull(frame, "frame");
            room(size);
            if (!Double.isFinite(rise + upturn + thickness) || rise < 0 || upturn < 0 || thickness <= 0) {
                throw new IllegalArgumentException("a curved hip needs finite non-negative rises and positive thickness");
            }
        }

        @Override
        public BoundingBox bounds() {
            return around(frame, size.getX(), rise + upturn + thickness, size.getZ());
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 local = frame.local(x, y, z);
            if (!over(local, size)) {
                return false;
            }
            double u = Math.abs(2 * local.x / size.getX() - 1);
            double v = Math.abs(2 * local.z / size.getZ() - 1);
            double inward = 1 - Math.max(u, v);
            double corner = u * v;
            double base = rise * inward * inward + upturn * Math.pow(corner, 4);
            return local.y >= base && local.y < base + thickness;
        }

        @Override
        public int coverage(SampleBox box) {
            SampleBox p = box.local(frame);
            if (p == null) {
                return -1;
            }
            int within = p.within(0, Double.NEGATIVE_INFINITY, 0,
                size.getX(), Double.POSITIVE_INFINITY, size.getZ());
            if (within == 0) {
                return 0;
            }
            double u0 = Math.sqrt(nearSquare(2 * p.x0 / size.getX() - 1, 2 * p.x1 / size.getX() - 1));
            double u1 = Math.max(Math.abs(2 * p.x0 / size.getX() - 1), Math.abs(2 * p.x1 / size.getX() - 1));
            double v0 = Math.sqrt(nearSquare(2 * p.z0 / size.getZ() - 1, 2 * p.z1 / size.getZ() - 1));
            double v1 = Math.max(Math.abs(2 * p.z0 / size.getZ() - 1), Math.abs(2 * p.z1 / size.getZ() - 1));
            // Independent extrema widen the height interval even when its two terms are correlated.
            double low = rise * nearSquare(1 - Math.max(u1, v1), 1 - Math.max(u0, v0))
                + upturn * Math.pow(u0 * v0, 4);
            double high = rise * farSquare(1 - Math.max(u1, v1), 1 - Math.max(u0, v0))
                + upturn * Math.pow(u1 * v1, 4);
            return band(p.y0, p.y1, low, high, thickness, within == 1);
        }
    }

    record FractionalBox(Frame frame, Vec3 low, Vec3 high) implements Solid {

        public FractionalBox {
            Objects.requireNonNull(frame, "frame");
            if (!Double.isFinite(low.x + low.y + low.z + high.x + high.y + high.z)
                    || high.x <= low.x || high.y <= low.y || high.z <= low.z) {
                throw new IllegalArgumentException("a fractional box needs finite, increasing limits");
            }
        }

        @Override
        public BoundingBox bounds() {
            return around(frame.moved(low.x, low.y, low.z), high.x - low.x, high.y - low.y, high.z - low.z);
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 local = frame.local(x, y, z);
            return local.x >= low.x && local.x < high.x && local.y >= low.y && local.y < high.y
                && local.z >= low.z && local.z < high.z;
        }

        @Override
        public int coverage(SampleBox box) {
            SampleBox p = box.local(frame);
            return p == null ? -1 : p.within(low.x, low.y, low.z, high.x, high.y, high.z);
        }
    }

    record Cuboid(BoundingBox box) implements Solid {

        public Cuboid {
            Objects.requireNonNull(box, "box");
        }

        @Override
        public BoundingBox bounds() {
            return box;
        }

        @Override
        public boolean holds(double x, double y, double z) {
            return inside(box, x, y, z);
        }

        @Override
        public int coverage(SampleBox box) {
            return box.within(this.box.minX(), this.box.minY(), this.box.minZ(),
                this.box.maxX() + 1.0, this.box.maxY() + 1.0, this.box.maxZ() + 1.0);
        }
    }

    record ProfiledSweep(Frame frame, List<Vec3> points, double width, double elevation, double rise,
            double upturn, double thickness, boolean flatEnds, BoundingBox bounds) implements Solid {

        public ProfiledSweep {
            points = List.copyOf(points);
            if (points.size() < 2 || !Double.isFinite(width + elevation + rise + upturn + thickness)
                    || width <= 0 || rise < 0 || upturn < 0 || thickness <= 0) {
                throw new IllegalArgumentException("a profiled sweep needs a line, positive width and thickness");
            }
        }

        public ProfiledSweep(Frame frame, List<Vec3> points, double width, double elevation, double rise,
                double upturn, double thickness) {
            this(frame, points, width, elevation, rise, upturn, thickness, false);
        }

        public ProfiledSweep(Frame frame, List<Vec3> points, double width, double elevation, double rise,
                double upturn, double thickness, boolean flatEnds) {
            this(frame, points, width, elevation, rise, upturn, thickness, flatEnds,
                measure(frame, points, width, elevation, rise, upturn, thickness));
        }

        private static BoundingBox measure(Frame frame, List<Vec3> points, double width, double elevation,
                double rise, double upturn, double thickness) {
            double half = width / 2;
            Vec3 low = points.getFirst();
            double x0 = low.x, y0 = low.y, z0 = low.z;
            double x1 = x0, y1 = y0, z1 = z0;
            for (Vec3 p : points) {
                x0 = Math.min(x0, p.x);
                y0 = Math.min(y0, p.y);
                z0 = Math.min(z0, p.z);
                x1 = Math.max(x1, p.x);
                y1 = Math.max(y1, p.y);
                z1 = Math.max(z1, p.z);
            }
            return around(frame.moved(x0 - half, y0 + elevation, z0 - half), x1 - x0 + width,
                y1 - y0 + rise + upturn + thickness, z1 - z0 + width);
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 p = frame.local(x, y, z);
            if (flatEnds && (beyondEnd(p, points.getFirst(), points.get(1))
                    || beyondEnd(p, points.getLast(), points.get(points.size() - 2)))) {
                return false;
            }
            double nearest = Double.POSITIVE_INFINITY;
            double ground = 0;
            for (int i = 1; i < points.size(); i++) {
                Vec3 a = points.get(i - 1);
                Vec3 b = points.get(i);
                double dx = b.x - a.x;
                double dz = b.z - a.z;
                double length = dx * dx + dz * dz;
                if (length == 0) {
                    continue;
                }
                double t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.z - a.z) * dz) / length));
                double ox = p.x - a.x - t * dx;
                double oz = p.z - a.z - t * dz;
                double distance = ox * ox + oz * oz;
                if (distance < nearest) {
                    nearest = distance;
                    ground = a.y + t * (b.y - a.y);
                }
            }
            double u2 = nearest / (width * width / 4);
            double crown = 1 - u2;
            double edge = u2 * u2;
            double surface = ground + elevation + rise * crown * crown + upturn * edge * edge;
            return u2 <= 1 && p.y >= surface && p.y < surface + thickness;
        }

        private static boolean beyondEnd(Vec3 p, Vec3 end, Vec3 inward) {
            return (p.x - end.x) * (inward.x - end.x) + (p.z - end.z) * (inward.z - end.z) < 0;
        }

        @Override
        public int coverage(SampleBox box) {
            SampleBox p = box.local(frame);
            if (p == null) {
                return -1;
            }
            boolean endsInside = true;
            if (flatEnds) {
                for (int end : new int[] {0, points.size() - 1}) {
                    Vec3 a = points.get(end);
                    Vec3 b = points.get(end == 0 ? 1 : end - 1);
                    double dx = b.x - a.x;
                    double dz = b.z - a.z;
                    double low = Math.min((p.x0 - a.x) * dx, (p.x1 - a.x) * dx)
                        + Math.min((p.z0 - a.z) * dz, (p.z1 - a.z) * dz);
                    double high = Math.max((p.x0 - a.x) * dx, (p.x1 - a.x) * dx)
                        + Math.max((p.z0 - a.z) * dz, (p.z1 - a.z) * dz);
                    double margin = 1.0E-10 * Math.max(1,
                        Math.abs(dx) * (Math.max(Math.abs(p.x0), Math.abs(p.x1)) + Math.abs(a.x))
                        + Math.abs(dz) * (Math.max(Math.abs(p.z0), Math.abs(p.z1)) + Math.abs(a.z)));
                    if (high < -margin) {
                        return 0;
                    }
                    endsInside &= low > margin;
                }
            }
            // Distance to a union of segments is 1-Lipschitz. The enclosing ball bounds
            // every point of the sample box, including across nearest-segment changes.
            Vec3 center = new Vec3((p.x0 + p.x1) / 2, 0, (p.z0 + p.z1) / 2);
            double reach = Math.sqrt(Math.pow(p.x1 - p.x0, 2) + Math.pow(p.z1 - p.z0, 2)) / 2;
            double nearest = Double.POSITIVE_INFINITY;
            double scale = Math.max(1, center.length() + reach);
            double groundLow = Double.POSITIVE_INFINITY;
            double groundHigh = Double.NEGATIVE_INFINITY;
            for (int i = 1; i < points.size(); i++) {
                Vec3 a = points.get(i - 1);
                Vec3 b = points.get(i);
                Vec3 delta = new Vec3(b.x - a.x, 0, b.z - a.z);
                double length = delta.lengthSqr();
                if (length == 0) {
                    continue;
                }
                Vec3 origin = new Vec3(a.x, 0, a.z);
                double t = Math.clamp(center.subtract(origin).dot(delta) / length, 0, 1);
                nearest = Math.min(nearest, center.distanceToSqr(origin.add(delta.scale(t))));
                scale = Math.max(scale, a.length() + b.length());
                groundLow = Math.min(groundLow, Math.min(a.y, b.y));
                groundHigh = Math.max(groundHigh, Math.max(a.y, b.y));
            }
            double low = Math.max(0, Math.sqrt(nearest) - reach - 1.0E-10 * scale);
            double high = Math.sqrt(nearest) + reach + 1.0E-10 * scale;
            double half = width / 2;
            if (low > half) {
                return 0;
            }
            double u0 = low * low / (half * half);
            double u1 = high * high / (half * half);
            double baseLow = groundLow + elevation + rise * nearSquare(1 - u1, 1 - u0)
                + upturn * Math.pow(u0, 4);
            double baseHigh = groundHigh + elevation + rise * farSquare(1 - u1, 1 - u0)
                + upturn * Math.pow(u1, 4);
            return band(p.y0, p.y1, baseLow, baseHigh, thickness, endsInside && high < half);
        }
    }

    record Oriented(Frame frame, Vec3i size) implements Solid {

        public Oriented {
            Objects.requireNonNull(frame, "frame");
            room(size);
        }

        @Override
        public BoundingBox bounds() {
            return around(frame, size.getX(), size.getY(), size.getZ());
        }

        @Override
        public boolean holds(double x, double y, double z) {
            return within(frame.local(x, y, z), size);
        }

        @Override
        public int coverage(SampleBox box) {
            SampleBox p = box.local(frame);
            return p == null ? -1 : p.within(size);
        }
    }

    record TaperedBox(Frame frame, Vec3i size, double insetX, double insetZ) implements Solid {

        public TaperedBox {
            Objects.requireNonNull(frame, "frame");
            room(size);
            if (!Double.isFinite(insetX + insetZ) || insetX < 0 || insetZ < 0
                    || 2 * insetX >= size.getX() || 2 * insetZ >= size.getZ()) {
                throw new IllegalArgumentException("a tapered box needs a positive top rectangle");
            }
        }

        @Override
        public BoundingBox bounds() {
            return around(frame, size.getX(), size.getY(), size.getZ());
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 local = frame.local(x, y, z);
            double t = local.y / size.getY();
            return t >= 0 && t < 1 && local.x >= insetX * t && local.x < size.getX() - insetX * t
                && local.z >= insetZ * t && local.z < size.getZ() - insetZ * t;
        }

        @Override
        public int coverage(SampleBox box) {
            SampleBox p = box.local(frame);
            if (p == null) {
                return -1;
            }
            int within = p.within(size);
            if (within == 0) {
                return 0;
            }
            double a = Math.clamp(p.y0 / size.getY(), 0, 1);
            double b = Math.clamp(p.y1 / size.getY(), 0, 1);
            // Nested horizontal rectangles shrink monotonically with height.
            if (p.within(insetX * a, 0, insetZ * a,
                    size.getX() - insetX * a, size.getY(), size.getZ() - insetZ * a) == 0) {
                return 0;
            }
            return p.within(Math.nextUp(insetX * b), 0, Math.nextUp(insetZ * b),
                Math.nextDown(size.getX() - insetX * b), size.getY(),
                Math.nextDown(size.getZ() - insetZ * b)) == 1 ? 1 : -1;
        }
    }

    record Arched(Solid inner, Frame frame, double span, double rise, double end) implements Solid {
        public Arched {
            Objects.requireNonNull(inner, "inner");
            Objects.requireNonNull(frame, "frame");
            if (span <= 0 || rise < 0 || !Double.isFinite(span + rise + end)) {
                throw new IllegalArgumentException("an arch needs a positive span and a finite non-negative rise");
            }
        }

        @Override
        public BoundingBox bounds() {
            BoundingBox box = inner.bounds();
            return new BoundingBox(box.minX(), box.minY() + (int) Math.floor(Math.min(0, end)), box.minZ(),
                box.maxX(), box.maxY() + (int) Math.ceil(Math.max(0, end) + rise), box.maxZ());
        }

        @Override
        public boolean holds(double x, double y, double z) {
            double t = Math.max(0, Math.min(1, (frame.local(x, y, z).z - 0.5D) / span));
            return inner.holds(x, y - end * t - 4 * rise * t * (1 - t), z);
        }

        @Override
        public int coverage(SampleBox box) {
            SampleBox local = box.local(frame);
            if (local == null) {
                return -1;
            }
            double a = Math.clamp((local.z0 - 0.5D) / span, 0, 1);
            double b = Math.clamp((local.z1 - 0.5D) / span, 0, 1);
            // This parabola is concave: the minimum is at an end, the maximum may be at its vertex.
            double fa = end * a + 4 * rise * a * (1 - a);
            double fb = end * b + 4 * rise * b * (1 - b);
            double high = Math.max(fa, fb);
            if (rise > 0) {
                double peak = Math.clamp((end + 4 * rise) / (8 * rise), a, b);
                high = Math.max(high, end * peak + 4 * rise * peak * (1 - peak));
            }
            double margin = 1.0E-10 * Math.max(1, Math.abs(end) + rise);
            return inner.coverage(box.lowered(Math.min(fa, fb) - margin, high + margin));
        }
    }

    record Vault(Frame frame, Vec3i size, int rise, int grow) implements Solid {
        public Vault {
            Objects.requireNonNull(frame, "frame");
            room(size);
            if (rise < 1 || rise * 2 > size.getZ() || rise > size.getY() || grow < 0) {
                throw new IllegalArgumentException("a vault rises at least one cell, at most half its depth");
            }
        }

        private double radius() {
            double half = size.getZ() / 2.0D;
            return (half * half + (double) rise * rise) / (2.0D * rise);
        }

        @Override
        public BoundingBox bounds() {
            BoundingBox box = around(frame, size.getX(), size.getY() + grow, size.getZ());
            if (grow == 0) {
                return box;
            }
            double drop = radius() - rise;
            int spread = (int) Math.ceil(Math.sqrt(Math.pow(radius() + grow, 2) - drop * drop) - size.getZ() / 2.0D) + 1;
            return new BoundingBox(box.minX() - spread, box.minY(), box.minZ() - spread,
                box.maxX() + spread, box.maxY(), box.maxZ() + spread);
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 local = frame.local(x, y, z);
            if (local.x < 0 || local.x >= size.getX() || local.y < 0) {
                return false;
            }
            double spring = size.getY() - rise;
            if (local.y < spring) {
                return local.z >= 0 && local.z < size.getZ();
            }
            double r = radius();
            double dz = local.z - size.getZ() / 2.0D;
            double dy = local.y - (spring + rise - r);
            return Math.abs(dz) * (r - rise) <= size.getZ() / 2.0D * dy
                && dz * dz + dy * dy < (r + grow) * (r + grow);
        }

        @Override
        public int coverage(SampleBox box) {
            SampleBox p = box.local(frame);
            if (p == null) {
                return -1;
            }
            if (p.x1 < 0 || p.x0 >= size.getX() || p.y1 < 0) {
                return 0;
            }
            boolean inside = p.x0 >= 0 && p.x1 < size.getX() && p.y0 >= 0;
            double spring = size.getY() - rise;
            if (p.y1 < spring) {
                int across = p.within(0, 0, 0, size.getX(), spring, size.getZ());
                return across;
            }
            if (p.y0 < spring) {
                return -1;
            }
            double r = radius();
            double dz0 = p.z0 - size.getZ() / 2.0;
            double dz1 = p.z1 - size.getZ() / 2.0;
            double dy0 = p.y0 - (size.getY() - r);
            double dy1 = p.y1 - (size.getY() - r);
            // Above the spring the vault is the intersection of a circle and an angular wedge.
            double nearZ = Math.sqrt(nearSquare(dz0, dz1));
            double farZ = Math.max(Math.abs(dz0), Math.abs(dz1));
            double margin = 1.0E-10 * Math.max(1, r * r);
            if (nearZ * (r - rise) > size.getZ() / 2.0 * dy1 + margin) {
                return 0;
            }
            inside &= farZ * (r - rise) < size.getZ() / 2.0 * dy0 - margin;
            return radial(nearSquare(dz0, dz1) + nearSquare(dy0, dy1),
                farSquare(dz0, dz1) + farSquare(dy0, dy1), (r + grow) * (r + grow), inside);
        }
    }

    record Hollow(Frame frame, Vec3i size, int thickness) implements Solid {

        public Hollow {
            Objects.requireNonNull(frame, "frame");
            room(size);
            if (thickness < 1) {
                throw new IllegalArgumentException("wall thickness must be at least one block");
            }
        }

        @Override
        public BoundingBox bounds() {
            return around(frame, size.getX(), size.getY(), size.getZ());
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 local = frame.local(x, y, z);
            if (!within(local, size)) {
                return false;
            }
            return local.x < thickness || local.x >= size.getX() - thickness
                || local.z < thickness || local.z >= size.getZ() - thickness;
        }
    }

    record Gable(Frame frame, Vec3i size, int ridge, int rise, int run, int thickness) implements Solid {

        public Gable {
            Objects.requireNonNull(frame, "frame");
            room(size);
            if (ridge != 0 && ridge != 2) {
                throw new IllegalArgumentException("a ridge runs along the scope's own x or z");
            }
            pitch(rise, run, thickness);
        }

        int across() {
            return ridge == 0 ? size.getZ() : size.getX();
        }

        double peak() {
            return climbOver(rise, run, (across() - 1) / 2);
        }

        @Override
        public BoundingBox bounds() {
            return around(frame, size.getX(), thickness + peak(), size.getZ());
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 local = frame.local(x, y, z);
            if (!over(local, size)) {
                return false;
            }
            int along = (int) Math.floor(ridge == 0 ? local.z : local.x);
            return course(thickness, (double) rise * Math.min(along, across() - 1 - along) / run, local.y);
        }
    }

    record Shed(Frame frame, Vec3i size, int rise, int run, int thickness) implements Solid {

        public Shed {
            Objects.requireNonNull(frame, "frame");
            room(size);
            pitch(rise, run, thickness);
        }

        double peak() {
            return climbOver(rise, run, size.getZ() - 1);
        }

        @Override
        public BoundingBox bounds() {
            return around(frame, size.getX(), thickness + peak(), size.getZ());
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 local = frame.local(x, y, z);
            if (!over(local, size)) {
                return false;
            }
            int along = (int) Math.floor(local.z);
            return course(thickness, (double) rise * (size.getZ() - 1 - along) / run, local.y);
        }
    }

    record Hip(Frame frame, Vec3i size, int rise, int run, int thickness) implements Solid {

        public Hip {
            Objects.requireNonNull(frame, "frame");
            room(size);
            pitch(rise, run, thickness);
        }

        double peak() {
            return climbOver(rise, run, (Math.min(size.getX(), size.getZ()) - 1) / 2);
        }

        @Override
        public BoundingBox bounds() {
            return around(frame, size.getX(), thickness + peak(), size.getZ());
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 local = frame.local(x, y, z);
            if (!over(local, size)) {
                return false;
            }
            int cx = (int) Math.floor(local.x);
            int cz = (int) Math.floor(local.z);
            int nearest = Math.min(Math.min(cx, size.getX() - 1 - cx), Math.min(cz, size.getZ() - 1 - cz));
            return course(thickness, (double) rise * nearest / run, local.y);
        }
    }

    record Ellipsoid(Frame frame, Vec3i size, boolean dome) implements Solid {

        public Ellipsoid {
            Objects.requireNonNull(frame, "frame");
            room(size);
        }

        @Override
        public BoundingBox bounds() {
            return around(frame, size.getX(), size.getY(), size.getZ());
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 local = frame.local(x, y, z);
            if (!within(local, size)) {
                return false;
            }
            double rx = size.getX() / 2.0D;
            double rz = size.getZ() / 2.0D;
            double ry = dome ? size.getY() : size.getY() / 2.0D;
            double dx = (local.x - rx) / rx;
            double dy = (local.y - (dome ? 0.0D : ry)) / ry;
            double dz = (local.z - rz) / rz;
            return dx * dx + dy * dy + dz * dz <= 1.0D;
        }

        @Override
        public int coverage(SampleBox box) {
            SampleBox p = box.local(frame);
            if (p == null) {
                return -1;
            }
            int within = p.within(size);
            if (within == 0) {
                return 0;
            }
            double rx = size.getX() / 2.0;
            double ry = dome ? size.getY() : size.getY() / 2.0;
            double rz = size.getZ() / 2.0;
            double cy = dome ? 0 : ry;
            double near = nearSquare((p.x0 - rx) / rx, (p.x1 - rx) / rx)
                + nearSquare((p.y0 - cy) / ry, (p.y1 - cy) / ry)
                + nearSquare((p.z0 - rz) / rz, (p.z1 - rz) / rz);
            double far = farSquare((p.x0 - rx) / rx, (p.x1 - rx) / rx)
                + farSquare((p.y0 - cy) / ry, (p.y1 - cy) / ry)
                + farSquare((p.z0 - rz) / rz, (p.z1 - rz) / rz);
            return radial(near, far, 1, within == 1);
        }
    }

    record Cylinder(Frame frame, Vec3i size, int axis, boolean taper) implements Solid {

        public Cylinder {
            Objects.requireNonNull(frame, "frame");
            room(size);
            if (axis < 0 || axis > 2) {
                throw new IllegalArgumentException("a cylinder stands along the scope's own x, y or z");
            }
        }

        @Override
        public BoundingBox bounds() {
            return around(frame, size.getX(), size.getY(), size.getZ());
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 local = frame.local(x, y, z);
            if (!within(local, size)) {
                return false;
            }
            double[] point = {local.x, local.y, local.z};
            double[] span = {size.getX(), size.getY(), size.getZ()};
            double shrink = taper ? 1.0D - point[axis] / span[axis] : 1.0D;
            double sum = 0.0D;
            for (int slot = 0; slot < 3; slot++) {
                if (slot == axis) {
                    continue;
                }
                double radius = span[slot] / 2.0D * shrink;
                if (radius <= 0.0D) {
                    return false;
                }
                double offset = (point[slot] - span[slot] / 2.0D) / radius;
                sum += offset * offset;
            }
            return sum <= 1.0D;
        }

        @Override
        public int coverage(SampleBox box) {
            SampleBox p = box.local(frame);
            if (p == null) {
                return -1;
            }
            int within = p.within(size);
            if (within == 0) {
                return 0;
            }
            double[] low = {p.x0, p.y0, p.z0};
            double[] high = {p.x1, p.y1, p.z1};
            double[] spans = {size.getX(), size.getY(), size.getZ()};
            double small = taper ? 1 - high[axis] / spans[axis] : 1;
            double large = taper ? 1 - low[axis] / spans[axis] : 1;
            if (large <= 0) {
                return 0;
            }
            double near = 0;
            double far = 0;
            for (int slot = 0; slot < 3; slot++) {
                if (slot == axis) {
                    continue;
                }
                double radius = spans[slot] / 2.0;
                near += nearSquare((low[slot] - radius) / radius, (high[slot] - radius) / radius) / (large * large);
                far += farSquare((low[slot] - radius) / radius, (high[slot] - radius) / radius) / (small * small);
            }
            return radial(near, far, 1, within == 1 && small > 0);
        }
    }

    record Prism(List<double[]> outline, int minY, int maxY) implements Solid {

        public Prism {
            outline = List.copyOf(outline);
            if (outline.size() < 3) {
                throw new IllegalArgumentException("an outline has at least three corners");
            }
            if (maxY < minY) {
                throw new IllegalArgumentException("a prism is at least one cell tall");
            }
        }

        @Override
        public BoundingBox bounds() {
            double minX = Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            for (double[] corner : outline) {
                minX = Math.min(minX, corner[0]);
                maxX = Math.max(maxX, corner[0]);
                minZ = Math.min(minZ, corner[1]);
                maxZ = Math.max(maxZ, corner[1]);
            }
            return new BoundingBox((int) Math.floor(minX), minY, (int) Math.floor(minZ),
                (int) Math.ceil(maxX) - 1, maxY, (int) Math.ceil(maxZ) - 1);
        }

        @Override
        public boolean holds(double x, double y, double z) {
            if (y < minY || y >= maxY + 1) {
                return false;
            }
            boolean in = false;
            for (int at = 0, before = outline.size() - 1; at < outline.size(); before = at++) {
                double[] a = outline.get(at);
                double[] b = outline.get(before);
                if ((a[1] > z) != (b[1] > z) && x < (b[0] - a[0]) * (z - a[1]) / (b[1] - a[1]) + a[0]) {
                    in = !in;
                }
            }
            return in;
        }
    }

    record Sweep(List<double[]> line, double width, int height, int baseY, boolean slope, double offset)
        implements Solid {

        public Sweep {
            line = List.copyOf(line);
            if (line.size() < 2) {
                throw new IllegalArgumentException("a sweep follows a line of at least two points");
            }
            if (width <= 0 || height < 1) {
                throw new IllegalArgumentException("a sweep is at least one cell wide and one tall");
            }
        }

        @Override
        public BoundingBox bounds() {
            double reach = width / 2.0D + Math.abs(offset) + 1.0D;
            double minX = Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            double lowY = Double.MAX_VALUE;
            double highY = -Double.MAX_VALUE;
            for (double[] point : line) {
                minX = Math.min(minX, point[0]);
                maxX = Math.max(maxX, point[0]);
                minZ = Math.min(minZ, point[2]);
                maxZ = Math.max(maxZ, point[2]);
                lowY = Math.min(lowY, point[1]);
                highY = Math.max(highY, point[1]);
            }
            int bottom = slope ? (int) Math.floor(lowY) : baseY;
            int top = slope ? (int) Math.ceil(highY) + height : baseY + height - 1;
            return new BoundingBox((int) Math.floor(minX - reach), bottom, (int) Math.floor(minZ - reach),
                (int) Math.ceil(maxX + reach), top, (int) Math.ceil(maxZ + reach));
        }

        @Override
        public boolean holds(double x, double y, double z) {
            double half = width / 2.0D;
            for (int at = 1; at < line.size(); at++) {
                double[] a = line.get(at - 1);
                double[] b = line.get(at);
                double dx = b[0] - a[0];
                double dz = b[2] - a[2];
                double length = Math.sqrt(dx * dx + dz * dz);
                if (length == 0.0D) {
                    continue;
                }
                double ux = dx / length;
                double uz = dz / length;
                double t = (x - a[0]) * ux + (z - a[2]) * uz;
                double across = Math.abs((x - a[0]) * -uz + (z - a[2]) * ux - offset);
                boolean first = at == 1;
                boolean last = at == line.size() - 1;
                if (t < 0.0D) {
                    if (first || offset != 0.0D) {
                        if (t < -0.5D) {
                            continue;
                        }
                    } else if (along(line.get(at - 2), a, x, z) <= 1.0D) {
                        continue;
                    } else {
                        across = Math.hypot(x - a[0], z - a[2]);
                    }
                } else if (t > length) {
                    if (last || offset != 0.0D) {
                        if (t > length + 0.5D) {
                            continue;
                        }
                    } else if (along(b, line.get(at + 1), x, z) >= 0.0D) {
                        continue;
                    } else {
                        across = Math.hypot(x - b[0], z - b[2]);
                    }
                }
                if (across > half) {
                    continue;
                }
                double base = slope
                    ? a[1] + (b[1] - a[1]) * Math.max(0.0D, Math.min(1.0D, t / length))
                    : baseY;
                if (y >= base && y < base + height) {
                    return true;
                }
            }
            return false;
        }

        private static double along(double[] from, double[] to, double x, double z) {
            double dx = to[0] - from[0];
            double dz = to[2] - from[2];
            double squared = dx * dx + dz * dz;
            return squared == 0.0D ? 0.0D : ((x - from[0]) * dx + (z - from[2]) * dz) / squared;
        }
    }

    record Cells(Set<BlockPos> filled) implements Solid {

        public Cells {
            filled = Set.copyOf(filled);
            if (filled.isEmpty()) {
                throw new IllegalArgumentException("a region of no cells is not a region");
            }
        }

        @Override
        public BoundingBox bounds() {
            return BoundingBox.encapsulatingPositions(filled).orElseThrow();
        }

        @Override
        public boolean holds(double x, double y, double z) {
            return filled.contains(new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
        }

        @Override
        public int coverage(SampleBox box) {
            int x = (int) Math.floor(box.x0);
            int y = (int) Math.floor(box.y0);
            int z = (int) Math.floor(box.z0);
            if (x != Math.floor(box.x1) || y != Math.floor(box.y1) || z != Math.floor(box.z1)) {
                return -1;
            }
            return filled.contains(new BlockPos(x, y, z)) ? 1 : 0;
        }
    }

    record Group(List<Solid> parts) implements Solid {

        public Group {
            parts = List.copyOf(parts);
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("a group of no regions is not a region");
            }
        }

        @Override
        public BoundingBox bounds() {
            BoundingBox all = null;
            for (Solid part : parts) {
                all = all == null ? part.bounds() : union(all, part.bounds());
            }
            return all;
        }

        @Override
        public boolean holds(double x, double y, double z) {
            for (Solid part : parts) {
                if (part.holds(x, y, z)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int coverage(SampleBox box) {
            int result = 0;
            for (Solid part : parts) {
                int covered = part.coverage(box);
                if (covered == 1) {
                    return 1;
                }
                if (covered == -1) {
                    result = -1;
                }
            }
            return result;
        }
    }

    record Difference(Solid kept, Solid taken) implements Solid {

        public Difference {
            Objects.requireNonNull(kept, "kept");
            Objects.requireNonNull(taken, "taken");
        }

        @Override
        public BoundingBox bounds() {
            return kept.bounds();
        }

        @Override
        public boolean holds(double x, double y, double z) {
            return kept.holds(x, y, z) && !taken.holds(x, y, z);
        }

        @Override
        public int coverage(SampleBox box) {
            int keep = kept.coverage(box);
            if (keep == 0) {
                return 0;
            }
            int take = taken.coverage(box);
            return take == 1 ? 0 : keep == 1 && take == 0 ? 1 : -1;
        }
    }

    record Intersection(Solid one, Solid other) implements Solid {

        public Intersection {
            Objects.requireNonNull(one, "one");
            Objects.requireNonNull(other, "other");
            if (!one.bounds().intersects(other.bounds())) {
                throw new IllegalArgumentException("these two regions share no cell");
            }
        }

        @Override
        public BoundingBox bounds() {
            BoundingBox a = one.bounds();
            BoundingBox b = other.bounds();
            return new BoundingBox(Math.max(a.minX(), b.minX()), Math.max(a.minY(), b.minY()),
                Math.max(a.minZ(), b.minZ()), Math.min(a.maxX(), b.maxX()), Math.min(a.maxY(), b.maxY()),
                Math.min(a.maxZ(), b.maxZ()));
        }

        @Override
        public boolean holds(double x, double y, double z) {
            return one.holds(x, y, z) && other.holds(x, y, z);
        }

        @Override
        public int coverage(SampleBox box) {
            int first = one.coverage(box);
            if (first == 0) {
                return 0;
            }
            int second = other.coverage(box);
            return second == 0 ? 0 : first == 1 && second == 1 ? 1 : -1;
        }
    }

    record Under(Solid over, int floorY) implements Solid {

        private static final double STEP = 0.25D;

        public Under {
            Objects.requireNonNull(over, "over");
            if (over.bounds().maxY() < floorY) {
                throw new IllegalArgumentException("the region lies wholly below the floor it should stand on");
            }
        }

        @Override
        public BoundingBox bounds() {
            BoundingBox roof = over.bounds();
            return new BoundingBox(roof.minX(), floorY, roof.minZ(), roof.maxX(), roof.maxY(), roof.maxZ());
        }

        @Override
        public boolean holds(double x, double y, double z) {
            BoundingBox roof = over.bounds();
            if (y < floorY || x < roof.minX() || x >= roof.maxX() + 1 || z < roof.minZ() || z >= roof.maxZ() + 1
                || over.holds(x, y, z)) {
                return false;
            }
            for (double above = y + STEP; above < roof.maxY() + 1; above += STEP) {
                if (over.holds(x, above, z)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int coverage(SampleBox box) {
            BoundingBox roof = over.bounds();
            int within = box.within(roof.minX(), floorY, roof.minZ(), roof.maxX() + 1.0,
                roof.maxY() + 1.0, roof.maxZ() + 1.0);
            int covered = over.coverage(box);
            if (within == 0 || covered == 1) {
                return 0;
            }
            if (over.coverage(new SampleBox(box.x0, Math.nextDown(box.y0 + STEP), box.z0,
                    box.x1, roof.maxY() + 1.0, box.z1)) == 0) {
                return 0;
            }
            if (within == 1 && covered == 0) {
                // A common integer step is a witness for every y in this interval.
                for (double shift = STEP; box.y1 + shift < roof.maxY() + 1; shift += STEP) {
                    double margin = (shift / STEP + 1)
                        * Math.ulp(Math.max(Math.abs(box.y0), Math.abs(box.y1)) + shift);
                    if (over.coverage(new SampleBox(box.x0, box.y0 + shift - margin, box.z0,
                            box.x1, box.y1 + shift + margin, box.z1)) == 1) {
                        return 1;
                    }
                }
            }
            return -1;
        }
    }

    record Lifted(Solid inner, int minX, int minZ, int width, double[] lift, boolean smooth) implements Solid {

        public Lifted {
            Objects.requireNonNull(inner, "inner");
            if (width < 1 || lift.length == 0 || lift.length % width != 0) {
                throw new IllegalArgumentException("a lift names one height per column");
            }
        }

        private int depth() {
            return lift.length / width;
        }

        @Override
        public BoundingBox bounds() {
            BoundingBox box = inner.bounds();
            double low = Double.MAX_VALUE;
            double high = -Double.MAX_VALUE;
            for (double each : lift) {
                low = Math.min(low, each);
                high = Math.max(high, each);
            }
            return new BoundingBox(box.minX(), box.minY() + (int) Math.floor(low), box.minZ(),
                box.maxX(), box.maxY() + (int) Math.ceil(high), box.maxZ());
        }

        @Override
        public boolean holds(double x, double y, double z) {
            return inner.holds(x, y - liftAt(x, z), z);
        }

        private double liftAt(double x, double z) {
            if (!smooth) {
                return column((int) Math.floor(x) - minX, (int) Math.floor(z) - minZ);
            }
            double fx = x - 0.5D - minX;
            double fz = z - 0.5D - minZ;
            int x0 = (int) Math.floor(fx);
            int z0 = (int) Math.floor(fz);
            double tx = fx - x0;
            double tz = fz - z0;
            double top = column(x0, z0) * (1 - tx) + column(x0 + 1, z0) * tx;
            double bottom = column(x0, z0 + 1) * (1 - tx) + column(x0 + 1, z0 + 1) * tx;
            return top * (1 - tz) + bottom * tz;
        }

        private double column(int x, int z) {
            int cx = Math.max(0, Math.min(width - 1, x));
            int cz = Math.max(0, Math.min(depth() - 1, z));
            return lift[cz * width + cx];
        }

        @Override
        public int coverage(SampleBox box) {
            double low = Double.POSITIVE_INFINITY;
            double high = Double.NEGATIVE_INFINITY;
            int ax = (int) Math.floor(box.x0 - (smooth ? 0.5D : 0)) - minX;
            int bx = (int) Math.floor(box.x1 - (smooth ? 0.5D : 0)) - minX + (smooth ? 1 : 0);
            int az = (int) Math.floor(box.z0 - (smooth ? 0.5D : 0)) - minZ;
            int bz = (int) Math.floor(box.z1 - (smooth ? 0.5D : 0)) - minZ + (smooth ? 1 : 0);
            for (int z = Math.max(0, Math.min(depth() - 1, az)); z <= Math.max(0, Math.min(depth() - 1, bz)); z++) {
                for (int x = Math.max(0, Math.min(width - 1, ax)); x <= Math.max(0, Math.min(width - 1, bx)); x++) {
                    double value = column(x, z);
                    low = Math.min(low, value);
                    high = Math.max(high, value);
                }
            }
            // Bilinear interpolation is a convex combination of the surrounding column heights.
            double margin = 1.0E-10 * Math.max(1, Math.max(Math.abs(low), Math.abs(high)));
            return inner.coverage(box.lowered(low - margin, high + margin));
        }
    }

    record PointedVault(Frame frame, Vec3i size, double rise, double grow, double inset, boolean clipped) implements Solid {

        public PointedVault(Frame frame, Vec3i size, double rise, double grow) {
            this(frame, size, rise, grow, 0, false);
        }

        public PointedVault {
            Objects.requireNonNull(frame, "frame");
            room(size);
            if (!Double.isFinite(rise + grow + inset) || inset < 0
                    || inset * 2 >= Math.min(size.getX(), size.getZ())
                    || rise < (size.getZ() - inset * 2) / 2 || rise > size.getY() || grow < 0) {
                throw new IllegalArgumentException("a pointed vault rises between half its span and its height");
            }
        }

        @Override
        public BoundingBox bounds() {
            double half = size.getZ() / 2.0D - inset;
            double radius = (half * half + rise * rise) / (2 * half);
            double peak = Math.sqrt((radius + grow) * (radius + grow) - (radius - half) * (radius - half));
            return around(frame.moved(inset, 0, inset - grow), size.getX() - 2 * inset, size.getY() - rise + peak,
                size.getZ() - 2 * inset + 2 * grow);
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 p = frame.local(x, y, z);
            if (p.x < inset || p.x >= size.getX() - inset || p.y < 0) {
                return false;
            }
            if (clipped && (p.z < inset || p.z >= size.getZ() - inset)) {
                return false;
            }
            double half = size.getZ() / 2.0D - inset;
            double spring = size.getY() - rise;
            if (p.y < spring) {
                return p.z >= inset - grow && p.z < size.getZ() - inset + grow;
            }
            double radius = (half * half + rise * rise) / (2 * half);
            double dz = Math.abs(p.z - size.getZ() / 2.0D) + radius - half;
            double dy = p.y - spring;
            return dz * dz + dy * dy <= (radius + grow) * (radius + grow);
        }

        @Override
        public int coverage(SampleBox box) {
            SampleBox p = box.local(frame);
            if (p == null) {
                return -1;
            }
            double zlow = clipped ? inset : Double.NEGATIVE_INFINITY;
            double zhigh = clipped ? size.getZ() - inset : Double.POSITIVE_INFINITY;
            int within = p.within(inset, 0, zlow, size.getX() - inset, Double.POSITIVE_INFINITY, zhigh);
            if (within == 0) {
                return 0;
            }
            double spring = size.getY() - rise;
            if (p.y1 < spring) {
                return p.within(inset, 0, Math.max(zlow, inset - grow), size.getX() - inset, spring,
                    Math.min(zhigh, size.getZ() - inset + grow));
            }
            if (p.y0 < spring) {
                return -1;
            }
            double half = size.getZ() / 2.0 - inset;
            double radius = (half * half + rise * rise) / (2 * half);
            double dz0 = Math.sqrt(nearSquare(p.z0 - size.getZ() / 2.0, p.z1 - size.getZ() / 2.0)) + radius - half;
            double dz1 = Math.max(Math.abs(p.z0 - size.getZ() / 2.0), Math.abs(p.z1 - size.getZ() / 2.0))
                + radius - half;
            return radial(dz0 * dz0 + nearSquare(p.y0 - spring, p.y1 - spring),
                dz1 * dz1 + farSquare(p.y0 - spring, p.y1 - spring),
                (radius + grow) * (radius + grow), within == 1);
        }
    }

    record Tube(Frame frame, List<Vec3> points, double radius, BoundingBox bounds) implements Solid {

        public Tube(Frame frame, List<Vec3> points, double radius) {
            this(frame, points, radius, tubeBounds(frame, points, radius));
        }

        public Tube {
            Objects.requireNonNull(frame, "frame");
            points = List.copyOf(points);
            if (points.size() < 2 || !Double.isFinite(radius) || radius <= 0) {
                throw new IllegalArgumentException("a tube needs two points and a positive finite radius");
            }
            for (Vec3 point : points) {
                if (!Double.isFinite(point.x + point.y + point.z)) {
                    throw new IllegalArgumentException("tube points must be finite");
                }
            }
        }

        private static BoundingBox tubeBounds(Frame frame, List<Vec3> points, double radius) {
            BoundingBox all = null;
            for (Vec3 point : points) {
                BoundingBox box = around(frame.moved(point.x - radius, point.y - radius, point.z - radius),
                    radius * 2, radius * 2, radius * 2);
                all = all == null ? box : union(all, box);
            }
            return all;
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 p = frame.local(x, y, z);
            for (int i = 1; i < points.size(); i++) {
                Vec3 a = points.get(i - 1);
                Vec3 delta = points.get(i).subtract(a);
                double length = delta.lengthSqr();
                double t = length == 0 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dot(delta) / length));
                if (p.distanceToSqr(a.add(delta.scale(t))) <= radius * radius) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int coverage(SampleBox box) {
            SampleBox p = box.local(frame);
            if (p == null) {
                return -1;
            }
            // Distance to a union of segments is 1-Lipschitz. The enclosing ball bounds
            // every point of the sample box, including across nearest-segment changes.
            Vec3 center = new Vec3((p.x0 + p.x1) / 2, (p.y0 + p.y1) / 2, (p.z0 + p.z1) / 2);
            double reach = Math.sqrt(Math.pow(p.x1 - p.x0, 2) + Math.pow(p.y1 - p.y0, 2)
                + Math.pow(p.z1 - p.z0, 2)) / 2;
            double nearest = Double.POSITIVE_INFINITY;
            double scale = Math.max(1, center.length() + reach);
            for (int i = 1; i < points.size(); i++) {
                Vec3 a = points.get(i - 1);
                Vec3 b = points.get(i);
                Vec3 delta = b.subtract(a);
                double length = delta.lengthSqr();
                double t = length == 0 ? 0 : Math.clamp(center.subtract(a).dot(delta) / length, 0, 1);
                nearest = Math.min(nearest, center.distanceToSqr(a.add(delta.scale(t))));
                scale = Math.max(scale, a.length() + b.length());
            }
            double low = Math.max(0, Math.sqrt(nearest) - reach - 1.0E-10 * scale);
            double high = Math.sqrt(nearest) + reach + 1.0E-10 * scale;
            return low > radius ? 0 : high < radius ? 1 : -1;
        }
    }

    record PitchedRoof(Frame frame, Vec3i size, double rise, double thickness) implements Solid {

        public PitchedRoof {
            Objects.requireNonNull(frame, "frame");
            room(size);
            if (!Double.isFinite(rise + thickness) || rise < 0 || thickness <= 0) {
                throw new IllegalArgumentException("a roof has a finite non-negative rise and positive thickness");
            }
        }

        @Override
        public BoundingBox bounds() {
            return around(frame, size.getX(), rise + thickness, size.getZ());
        }

        @Override
        public boolean holds(double x, double y, double z) {
            Vec3 p = frame.local(x, y, z);
            if (p.x < 0 || p.x >= size.getX() || p.z < 0 || p.z >= size.getZ()) {
                return false;
            }
            double half = size.getX() / 2.0D;
            double top = thickness + rise * (1 - Math.abs(p.x - half) / half);
            return p.y >= top - thickness && p.y < top;
        }

        @Override
        public int coverage(SampleBox box) {
            SampleBox p = box.local(frame);
            if (p == null) {
                return -1;
            }
            int within = p.within(0, Double.NEGATIVE_INFINITY, 0,
                size.getX(), Double.POSITIVE_INFINITY, size.getZ());
            if (within == 0) {
                return 0;
            }
            double half = size.getX() / 2.0;
            double low = rise * (1 - Math.max(Math.abs(p.x0 - half), Math.abs(p.x1 - half)) / half);
            double high = rise * (1 - Math.sqrt(nearSquare(p.x0 - half, p.x1 - half)) / half);
            return band(p.y0, p.y1, low, high, thickness, within == 1);
        }
    }

    static BoundingBox union(BoundingBox one, BoundingBox other) {
        return new BoundingBox(Math.min(one.minX(), other.minX()), Math.min(one.minY(), other.minY()),
            Math.min(one.minZ(), other.minZ()), Math.max(one.maxX(), other.maxX()), Math.max(one.maxY(), other.maxY()),
            Math.max(one.maxZ(), other.maxZ()));
    }

    static BoundingBox around(Frame frame, double width, double height, double depth) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 at = frame.at((corner & 1) == 0 ? 0 : width, (corner & 2) == 0 ? 0 : height,
                (corner & 4) == 0 ? 0 : depth);
            minX = Math.min(minX, at.x);
            maxX = Math.max(maxX, at.x);
            minY = Math.min(minY, at.y);
            maxY = Math.max(maxY, at.y);
            minZ = Math.min(minZ, at.z);
            maxZ = Math.max(maxZ, at.z);
        }
        return new BoundingBox((int) Math.floor(minX), (int) Math.floor(minY), (int) Math.floor(minZ),
            (int) Math.ceil(maxX) - 1, (int) Math.ceil(maxY) - 1, (int) Math.ceil(maxZ) - 1);
    }

    private static void room(Vec3i size) {
        if (size.getX() < 1 || size.getY() < 1 || size.getZ() < 1) {
            throw new IllegalArgumentException("a region measures at least one cell on every axis");
        }
    }

    private static boolean within(Vec3 local, Vec3i size) {
        return local.x >= 0 && local.x < size.getX() && local.y >= 0 && local.y < size.getY()
            && local.z >= 0 && local.z < size.getZ();
    }

    private static boolean over(Vec3 local, Vec3i size) {
        return local.x >= 0 && local.x < size.getX() && local.z >= 0 && local.z < size.getZ();
    }

    private static void pitch(int rise, int run, int thickness) {
        if (rise < 0 || run < 1) {
            throw new IllegalArgumentException("pitch must be a non-negative rise over a positive run");
        }
        if (rise != 0 && rise != run && rise * 2 != run && rise != run * 2) {
            throw new IllegalArgumentException("a pitch of " + rise + " over " + run + " cannot be laid in"
                + " half blocks; use 1:1, 1:2 or 2:1");
        }
        if (thickness < 1) {
            throw new IllegalArgumentException("a roof is at least one course deep");
        }
    }

    static int climbOver(int rise, int run, int cells) {
        return (int) Math.ceil((double) rise * cells / run);
    }

    private static boolean course(int thickness, double climb, double y) {
        double top = thickness + climb;
        return y < top && y >= Math.ceil(top) - thickness;
    }

    private static boolean inside(BoundingBox box, double x, double y, double z) {
        return x >= box.minX() && x < box.maxX() + 1
            && y >= box.minY() && y < box.maxY() + 1
            && z >= box.minZ() && z < box.maxZ() + 1;
    }
}
