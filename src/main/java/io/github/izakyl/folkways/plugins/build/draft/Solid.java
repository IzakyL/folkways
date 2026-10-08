package io.github.izakyl.folkways.plugins.build.draft;

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
