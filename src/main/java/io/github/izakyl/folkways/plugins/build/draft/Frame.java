package io.github.izakyl.folkways.plugins.build.draft;

import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

public record Frame(Vec3 origin, Vec3 right, Vec3 up, Vec3 forward) {

    private static final double NEARLY = 1.0E-9;

    public Frame {
        origin = snapped(origin);
        right = snapped(unit(right));
        up = snapped(unit(up));
        forward = snapped(unit(forward));
    }

    static Frame over(BoundingBox box, Direction facing, boolean mirrored) {
        Direction across = mirrored ? facing.getCounterClockWise() : facing.getClockWise();
        Vec3 right = way(across);
        Vec3 up = way(Direction.UP);
        Vec3 forward = way(facing);
        return new Frame(corner(box, right, up, forward), right, up, forward);
    }

    private static Vec3 corner(BoundingBox box, Vec3 right, Vec3 up, Vec3 forward) {
        return new Vec3(start(box.minX(), box.maxX(), right.x + up.x + forward.x),
            start(box.minY(), box.maxY(), right.y + up.y + forward.y),
            start(box.minZ(), box.maxZ(), right.z + up.z + forward.z));
    }

    private static double start(int min, int max, double runs) {
        return runs >= 0 ? min : max + 1;
    }

    static Vec3 way(Direction direction) {
        return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    Vec3 at(double x, double y, double z) {
        return new Vec3(origin.x + right.x * x + up.x * y + forward.x * z,
            origin.y + right.y * x + up.y * y + forward.y * z,
            origin.z + right.z * x + up.z * y + forward.z * z);
    }

    Vec3 local(double x, double y, double z) {
        double dx = x - origin.x;
        double dy = y - origin.y;
        double dz = z - origin.z;
        return new Vec3(dx * right.x + dy * right.y + dz * right.z,
            dx * up.x + dy * up.y + dz * up.z,
            dx * forward.x + dy * forward.y + dz * forward.z);
    }

    Frame moved(double x, double y, double z) {
        return new Frame(at(x, y, z), right, up, forward);
    }

    Frame raised(double cells) {
        return new Frame(origin.add(0.0D, cells, 0.0D), right, up, forward);
    }

    Frame flipped(int width) {
        return new Frame(at(width, 0, 0), right.reverse(), up, forward);
    }

    Frame quartered(int turns) {
        Vec3 acrossNow = right;
        Vec3 forwardNow = forward;
        for (int turn = 0; turn < Math.floorMod(turns, 4); turn++) {
            Vec3 wasRight = acrossNow;
            acrossNow = forwardNow.reverse();
            forwardNow = wasRight;
        }
        return new Frame(origin, acrossNow, up, forwardNow);
    }

    Frame turned(Vec3 axis, double degrees, Vec3 about) {
        double radians = Math.toRadians(degrees);
        Vec3 spun = rotate(about.subtract(origin).reverse(), axis, radians);
        return new Frame(about.add(spun), rotate(right, axis, radians), rotate(up, axis, radians),
            rotate(forward, axis, radians));
    }

    private static Vec3 rotate(Vec3 vector, Vec3 axis, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return vector.scale(cos)
            .add(axis.cross(vector).scale(sin))
            .add(axis.scale(axis.dot(vector) * (1.0D - cos)));
    }

    boolean square() {
        return whole(right) && whole(up) && whole(forward);
    }

    private static boolean whole(Vec3 axis) {
        return Math.abs(axis.x) + Math.abs(axis.y) + Math.abs(axis.z) == 1.0D;
    }

    Direction facing() {
        return nearest(forward);
    }

    static Direction nearest(Vec3 way) {
        Direction found = Direction.NORTH;
        double best = -Double.MAX_VALUE;
        for (Direction direction : Direction.values()) {
            double along = way.dot(way(direction));
            if (along > best) {
                best = along;
                found = direction;
            }
        }
        return found;
    }

    static Direction compass(Vec3 way) {
        Direction found = Direction.NORTH;
        double best = -Double.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            double along = way.dot(way(direction));
            if (along > best) {
                best = along;
                found = direction;
            }
        }
        return found;
    }

    private static Vec3 unit(Vec3 axis) {
        double length = axis.length();
        if (length < NEARLY) {
            throw new IllegalArgumentException("a frame's axes each point somewhere");
        }
        return length == 1.0D ? axis : axis.scale(1.0D / length);
    }

    private static Vec3 snapped(Vec3 point) {
        return new Vec3(snap(point.x), snap(point.y), snap(point.z));
    }

    private static double snap(double value) {
        double whole = Math.rint(value);
        return Math.abs(value - whole) < NEARLY ? whole : value;
    }
}
