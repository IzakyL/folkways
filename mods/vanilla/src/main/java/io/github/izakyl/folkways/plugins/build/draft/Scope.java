package io.github.izakyl.folkways.plugins.build.draft;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkValue;

@StarlarkBuiltin(name = "scope", doc = "A room of space, the way it faces, and its own axes.")
public record Scope(Frame frame, Vec3i size, String name, String face, boolean mirrored) implements StarlarkValue {

    public Scope {
        if (size.getX() < 1 || size.getY() < 1 || size.getZ() < 1) {
            throw new IllegalArgumentException("a scope measures at least one cell on every axis");
        }
        name = name == null ? "" : name;
        face = face == null ? "" : face;
    }

    public static Scope over(BoundingBox box) {
        return over(box, Direction.NORTH);
    }

    static Scope over(BoundingBox box, Direction facing) {
        return new Scope(Frame.over(box, facing, false),
            new Vec3i(span(box, facing.getClockWise().getAxis()), span(box, Direction.Axis.Y),
                span(box, facing.getAxis())), "", "", false);
    }

    static Scope of(Frame frame, int width, int height, int depth, String name, String face, boolean mirrored) {
        return new Scope(frame, new Vec3i(width, height, depth), name, face, mirrored);
    }

    int span(int axis) {
        return axis == 0 ? size.getX() : axis == 1 ? size.getY() : size.getZ();
    }

    public BoundingBox bounds() {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 at = frame.at((corner & 1) == 0 ? 0 : size.getX(), (corner & 2) == 0 ? 0 : size.getY(),
                (corner & 4) == 0 ? 0 : size.getZ());
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

    Solid solid() {
        return new Solid.Oriented(frame, size);
    }

    boolean holds(double x, double y, double z) {
        Vec3 local = frame.local(x, y, z);
        return local.x >= 0 && local.x < size.getX() && local.y >= 0 && local.y < size.getY()
            && local.z >= 0 && local.z < size.getZ();
    }

    void forEachCell(Cells visitor) {
        BoundingBox box = bounds();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    if (holds(x + 0.5D, y + 0.5D, z + 0.5D)) {
                        visitor.at(x, y, z);
                    }
                }
            }
        }
    }

    @FunctionalInterface
    interface Cells {
        void at(int x, int y, int z);
    }

    Vec3 right() {
        return frame.right();
    }

    Vec3 up() {
        return frame.up();
    }

    Vec3 forward() {
        return frame.forward();
    }

    int along(String axis) throws EvalException {
        int found = "xyz".indexOf(axis.toLowerCase(Locale.ROOT));
        if (found < 0 || axis.length() != 1) {
            throw Starlark.errorf("an axis is 'x', 'y' or 'z', not '%s'", axis);
        }
        return found;
    }

    Vec3 axis(int along) {
        return along == 0 ? frame.right() : along == 1 ? frame.up() : frame.forward();
    }

    Scope named(String renamed) {
        return new Scope(frame, size, renamed, face, mirrored);
    }

    Scope slice(int axis, int offset, int cells) {
        Frame moved = frame.moved(axis == 0 ? offset : 0, axis == 1 ? offset : 0, axis == 2 ? offset : 0);
        return framed(moved, new Vec3i(axis == 0 ? cells : size.getX(), axis == 1 ? cells : size.getY(),
            axis == 2 ? cells : size.getZ()));
    }

    Vec3 pointAt(int axis, int offset) {
        return frame.at(axis == 0 ? offset : 0, axis == 1 ? offset : 0, axis == 2 ? offset : 0);
    }

    Scope framed(Frame moved, Vec3i measured) {
        return new Scope(moved, measured, name, face, mirrored);
    }

    Vec3 outward() {
        return switch (face) {
            case "front", "back", "left", "right", "slope", "side" -> frame.forward();
            case "top" -> frame.up();
            case "bottom" -> frame.up().reverse();
            default -> null;
        };
    }

    BlockPos cell(int x, int y, int z) {
        Vec3 at = frame.at(x + 0.5D, y + 0.5D, z + 0.5D);
        return new BlockPos((int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z));
    }

    @StarlarkMethod(name = "width", doc = "How many cells the scope runs along its own x.", structField = true)
    public StarlarkInt width() {
        return StarlarkInt.of(size.getX());
    }

    @StarlarkMethod(name = "height", doc = "How many cells the scope runs along its own y.", structField = true)
    public StarlarkInt height() {
        return StarlarkInt.of(size.getY());
    }

    @StarlarkMethod(name = "depth", doc = "How many cells the scope runs along its own z.", structField = true)
    public StarlarkInt depth() {
        return StarlarkInt.of(size.getZ());
    }

    @StarlarkMethod(name = "name", doc = "The name a split or a comp gave this scope, or an empty string.",
        structField = true)
    public String nameField() {
        return name;
    }

    @StarlarkMethod(name = "face", doc = "Which face of a parent this was cut from, or an empty string.",
        structField = true)
    public String faceName() {
        return face;
    }

    @StarlarkMethod(name = "facing", doc = "The compass direction the scope's own z runs nearest to: north,"
        + " east, south or west.", structField = true)
    public String facingName() {
        return Frame.compass(frame.forward()).getName();
    }

    @StarlarkMethod(name = "mirrored", doc = "Whether this scope's own x runs the other way round.",
        structField = true)
    public boolean isMirrored() {
        return mirrored;
    }

    @StarlarkMethod(name = "square", doc = "Whether the scope's own axes run along the world's own, so that"
        + " everything laid in it falls on whole cells.", structField = true)
    public boolean isSquare() {
        return frame.square();
    }

    @StarlarkMethod(name = "tilt", doc = "How many degrees the scope's own y leans away from straight up.",
        structField = true)
    public StarlarkFloat tilt() {
        return StarlarkFloat.of(Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, frame.up().y)))));
    }

    @StarlarkMethod(name = "origin", doc = "The cell the scope's own axes start at.", structField = true)
    public Point origin() {
        return Point.of(cell(0, 0, 0));
    }

    @StarlarkMethod(
        name = "center",
        doc = "The middle cell. Where a span is even there are two middles; parity picks 'low' (the default) or 'high'.",
        parameters = {@Param(name = "parity", defaultValue = "'low'", named = true, positional = false)})
    public Point center(String parity) throws EvalException {
        return Point.of(cell(middle(size.getX(), parity), middle(size.getY(), parity), middle(size.getZ(), parity)));
    }

    private static int middle(int span, String parity) throws EvalException {
        return switch (parity.toLowerCase(Locale.ROOT)) {
            case "low" -> (span - 1) / 2;
            case "high" -> span / 2;
            default -> throw Starlark.errorf("parity is 'low' or 'high', not '%s'", parity);
        };
    }

    @StarlarkMethod(
        name = "at",
        doc = "The same room of space, moved along the scope's own axes.",
        parameters = {
            @Param(name = "x", defaultValue = "0", named = true, positional = false),
            @Param(name = "y", defaultValue = "0", named = true, positional = false),
            @Param(name = "z", defaultValue = "0", named = true, positional = false)})
    public Scope at(StarlarkInt x, StarlarkInt y, StarlarkInt z) throws EvalException {
        return framed(frame.moved(x.toInt("x"), y.toInt("y"), z.toInt("z")), size);
    }

    @StarlarkMethod(
        name = "sized",
        doc = "The same room of space resized. Zero keeps an axis. anchor keeps the 'start' (the default),"
            + " 'center' or 'end' of each resized axis where it is; a centre that falls between cells needs"
            + " parity 'low' or 'high'.",
        parameters = {
            @Param(name = "width", defaultValue = "0", named = true, positional = false),
            @Param(name = "height", defaultValue = "0", named = true, positional = false),
            @Param(name = "depth", defaultValue = "0", named = true, positional = false),
            @Param(name = "anchor", defaultValue = "'start'", named = true, positional = false),
            @Param(name = "parity", defaultValue = "'error'", named = true, positional = false)})
    public Scope sized(StarlarkInt width, StarlarkInt height, StarlarkInt depth, String anchor, String parity)
            throws EvalException {
        int[] made = {size.getX(), size.getY(), size.getZ()};
        int[] wanted = {width.toInt("width"), height.toInt("height"), depth.toInt("depth")};
        double[] lead = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            if (wanted[axis] == 0) {
                continue;
            }
            if (wanted[axis] < 0) {
                throw Starlark.errorf("a size is at least one cell, got %d", wanted[axis]);
            }
            lead[axis] = leading(made[axis], wanted[axis], anchor, parity);
            made[axis] = wanted[axis];
        }
        return framed(frame.moved(lead[0], lead[1], lead[2]), new Vec3i(made[0], made[1], made[2]));
    }

    private static int leading(int span, int cells, String anchor, String parity) throws EvalException {
        return switch (anchor.toLowerCase(Locale.ROOT)) {
            case "start" -> 0;
            case "end" -> span - cells;
            case "center" -> {
                int spare = span - cells;
                if (Math.floorMod(spare, 2) != 0) {
                    yield switch (parity.toLowerCase(Locale.ROOT)) {
                        case "low" -> Math.floorDiv(spare, 2);
                        case "high" -> Math.floorDiv(spare, 2) + 1;
                        default -> throw Starlark.errorf("centring %d cells in %d leaves one cell over; pass"
                            + " parity = 'low' or 'high' to say which side takes it", cells, span);
                    };
                }
                yield spare / 2;
            }
            default -> throw Starlark.errorf("an anchor is 'start', 'center' or 'end', not '%s'", anchor);
        };
    }

    @StarlarkMethod(
        name = "inset",
        doc = "The same room of space drawn in: x, y and z from both ends of that axis, front, back, left,"
            + " right, top and bottom from one side only.",
        parameters = {
            @Param(name = "x", defaultValue = "0", named = true, positional = false),
            @Param(name = "y", defaultValue = "0", named = true, positional = false),
            @Param(name = "z", defaultValue = "0", named = true, positional = false),
            @Param(name = "front", defaultValue = "0", named = true, positional = false),
            @Param(name = "back", defaultValue = "0", named = true, positional = false),
            @Param(name = "left", defaultValue = "0", named = true, positional = false),
            @Param(name = "right", defaultValue = "0", named = true, positional = false),
            @Param(name = "top", defaultValue = "0", named = true, positional = false),
            @Param(name = "bottom", defaultValue = "0", named = true, positional = false)})
    public Scope inset(StarlarkInt x, StarlarkInt y, StarlarkInt z, StarlarkInt front, StarlarkInt back,
            StarlarkInt left, StarlarkInt right, StarlarkInt top, StarlarkInt bottom) throws EvalException {
        return drawnIn(1, x, y, z, front, back, left, right, top, bottom);
    }

    @StarlarkMethod(
        name = "outset",
        doc = "The same room of space grown outward, the opposite of inset.",
        parameters = {
            @Param(name = "x", defaultValue = "0", named = true, positional = false),
            @Param(name = "y", defaultValue = "0", named = true, positional = false),
            @Param(name = "z", defaultValue = "0", named = true, positional = false),
            @Param(name = "front", defaultValue = "0", named = true, positional = false),
            @Param(name = "back", defaultValue = "0", named = true, positional = false),
            @Param(name = "left", defaultValue = "0", named = true, positional = false),
            @Param(name = "right", defaultValue = "0", named = true, positional = false),
            @Param(name = "top", defaultValue = "0", named = true, positional = false),
            @Param(name = "bottom", defaultValue = "0", named = true, positional = false)})
    public Scope outset(StarlarkInt x, StarlarkInt y, StarlarkInt z, StarlarkInt front, StarlarkInt back,
            StarlarkInt left, StarlarkInt right, StarlarkInt top, StarlarkInt bottom) throws EvalException {
        return drawnIn(-1, x, y, z, front, back, left, right, top, bottom);
    }

    private Scope drawnIn(int sign, StarlarkInt x, StarlarkInt y, StarlarkInt z, StarlarkInt front,
            StarlarkInt back, StarlarkInt left, StarlarkInt right, StarlarkInt top, StarlarkInt bottom)
            throws EvalException {
        int[] low = {sign * (x.toInt("x") + left.toInt("left")), sign * (y.toInt("y") + bottom.toInt("bottom")),
            sign * (z.toInt("z") + back.toInt("back"))};
        int[] high = {sign * (x.toInt("x") + right.toInt("right")), sign * (y.toInt("y") + top.toInt("top")),
            sign * (z.toInt("z") + front.toInt("front"))};
        int[] made = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            made[axis] = span(axis) - low[axis] - high[axis];
            if (made[axis] < 1) {
                throw Starlark.errorf("that leaves a scope with no cells on one of its axes");
            }
        }
        return framed(frame.moved(low[0], low[1], low[2]), new Vec3i(made[0], made[1], made[2]));
    }

    @StarlarkMethod(
        name = "rotate",
        doc = "The same room of space, its frame turned this many quarter turns clockwise about its own y."
            + " What it covers does not move; its own x and z trade places.",
        parameters = {@Param(name = "turns")})
    public Scope rotate(StarlarkInt turns) throws EvalException {
        Frame turned = frame;
        Vec3i measured = size;
        for (int turn = 0; turn < Math.floorMod(turns.toInt("turns"), 4); turn++) {
            turned = turned.moved(0, 0, measured.getZ()).quartered(1);
            measured = new Vec3i(measured.getZ(), measured.getY(), measured.getX());
        }
        return framed(turned, measured);
    }

    @StarlarkMethod(
        name = "pivot",
        doc = "The same room of space turned by any angle about one of its own axes — 'x' to tip it forward,"
            + " 'y' to swing it round, 'z' to roll it, the way a clock's hands run when you look back down"
            + " that axis, as rotate turns it. about turns it round its 'center' (the default) or its 'origin'."
            + " What it covers moves with it, so what is built in it comes out on a slant.",
        parameters = {
            @Param(name = "degrees"),
            @Param(name = "axis", defaultValue = "'y'", named = true, positional = false),
            @Param(name = "about", defaultValue = "'center'", named = true, positional = false)})
    public Scope pivot(Object degrees, String axis, String about) throws EvalException {
        double turn = number(degrees, "degrees");
        Vec3 around = switch (about.toLowerCase(Locale.ROOT)) {
            case "center" -> frame.at(size.getX() / 2.0D, size.getY() / 2.0D, size.getZ() / 2.0D);
            case "origin" -> frame.origin();
            default -> throw Starlark.errorf("a pivot is about 'center' or 'origin', not '%s'", about);
        };
        return framed(frame.turned(axis(along(axis)), -turn, around), size);
    }

    @StarlarkMethod(
        name = "mirror",
        doc = "The same room of space with its own x running the other way; what is stamped into it comes out"
            + " mirrored.")
    public Scope mirror() {
        return new Scope(frame.flipped(size.getX()), size, name, face, !mirrored);
    }

    static double number(Object written, String what) throws EvalException {
        if (written instanceof StarlarkInt whole) {
            return whole.toInt(what);
        }
        if (written instanceof StarlarkFloat part) {
            return part.toDouble();
        }
        throw Starlark.errorf("%s is a number, got %s", what, Starlark.type(written));
    }

    static int span(BoundingBox box, Direction.Axis axis) {
        return switch (axis) {
            case X -> box.maxX() - box.minX() + 1;
            case Y -> box.maxY() - box.minY() + 1;
            case Z -> box.maxZ() - box.minZ() + 1;
        };
    }

    @Override
    public boolean isImmutable() {
        return true;
    }

    @Override
    public void repr(Printer printer) {
        BlockPos start = cell(0, 0, 0);
        printer.append("scope(" + (name.isEmpty() ? "" : name + " ") + size.getX() + "x" + size.getY() + "x"
            + size.getZ() + " at " + start.getX() + "," + start.getY() + "," + start.getZ() + " facing "
            + facingName() + (frame.square() ? "" : " aslant") + (mirrored ? " mirrored" : "") + ")");
    }
}
