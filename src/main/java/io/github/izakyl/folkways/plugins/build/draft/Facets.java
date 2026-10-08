package io.github.izakyl.folkways.plugins.build.draft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;

final class Facets {

    static final List<String> SIDES = List.of("front", "back", "left", "right");
    static final List<String> ALL = List.of("front", "back", "left", "right", "top", "bottom");

    private Facets() {
    }

    static Scope roomOf(Solid solid) {
        return switch (solid) {
            case Solid.Oriented box -> room(box.frame(), box.size(), box.size().getY());
            case Solid.Hollow walls -> room(walls.frame(), walls.size(), walls.size().getY());
            case Solid.Ellipsoid ball -> room(ball.frame(), ball.size(), ball.size().getY());
            case Solid.Cylinder drum -> room(drum.frame(), drum.size(), drum.size().getY());
            case Solid.Gable roof -> room(roof.frame(), roof.size(),
                roof.thickness() + (int) Math.ceil(roof.peak()));
            case Solid.Shed roof -> room(roof.frame(), roof.size(),
                roof.thickness() + (int) Math.ceil(roof.peak()));
            case Solid.Hip roof -> room(roof.frame(), roof.size(),
                roof.thickness() + (int) Math.ceil(roof.peak()));
            default -> Scope.over(solid.bounds());
        };
    }

    private static Scope room(Frame frame, Vec3i size, int height) {
        return Scope.of(frame, size.getX(), Math.max(1, height), size.getZ(), "", "", false);
    }

    static List<String> offered(Solid solid) {
        return switch (solid) {
            case Solid.Oriented ignored -> ALL;
            case Solid.Hollow ignored -> ALL;
            case Solid.Gable ignored -> List.of("slopes", "ridge");
            case Solid.Shed ignored -> List.of("slopes", "ridge");
            case Solid.Hip ignored -> List.of("slopes");
            case Solid.Prism ignored -> List.of("sides", "top", "bottom");
            default -> List.of();
        };
    }

    static List<Scope> of(Solid solid, String selector, int thick, boolean mirrored) throws EvalException {
        String wanted = selector.toLowerCase(Locale.ROOT);
        return switch (solid) {
            case Solid.Oriented box -> box(box.frame(), box.size(), mirrored, wanted, thick);
            case Solid.Hollow walls -> box(walls.frame(), walls.size(), mirrored, wanted, thick);
            case Solid.Gable roof -> gable(roof, wanted, thick, mirrored);
            case Solid.Shed roof -> shed(roof, wanted, thick, mirrored);
            case Solid.Hip roof -> hip(roof, wanted, thick, mirrored);
            case Solid.Prism plan -> prism(plan, wanted, thick, mirrored);
            default -> throw Starlark.errorf("this shape has no faces to take: comp works on a scope, a box,"
                + " a roof or something stood up from an outline, which offer %s", offered(solid));
        };
    }

    private record Cut(int[] low, int[] size, int turns) {}

    static List<Scope> box(Frame frame, Vec3i size, boolean mirrored, String selector, int thick)
            throws EvalException {
        List<Scope> found = new ArrayList<>();
        switch (selector) {
            case "sides" -> {
                for (String name : SIDES) {
                    found.add(face(frame, size, mirrored, name, thick));
                }
            }
            case "all" -> {
                for (String name : ALL) {
                    found.add(face(frame, size, mirrored, name, thick));
                }
            }
            case "edges" -> {
                for (String side : List.of("front", "back")) {
                    for (String flank : List.of("left", "right")) {
                        found.add(meet(frame, size, mirrored, thick, side + "-" + flank, side, flank));
                    }
                }
                for (String level : List.of("top", "bottom")) {
                    for (String side : SIDES) {
                        found.add(meet(frame, size, mirrored, thick, level + "-" + side, side, level));
                    }
                }
            }
            case "corners" -> {
                for (String level : List.of("top", "bottom")) {
                    for (String side : List.of("front", "back")) {
                        for (String flank : List.of("left", "right")) {
                            found.add(meet(frame, size, mirrored, thick, level + "-" + side + "-" + flank,
                                side, level, flank));
                        }
                    }
                }
            }
            default -> {
                if (!ALL.contains(selector)) {
                    throw Starlark.errorf("comp takes 'sides', 'all', 'edges', 'corners' or one of %s, got '%s'",
                        ALL, selector);
                }
                found.add(face(frame, size, mirrored, selector, thick));
            }
        }
        return found;
    }

    private static Cut cutOf(Vec3i size, String face, int thick) {
        int width = size.getX();
        int height = size.getY();
        int depth = size.getZ();
        int acrossX = Math.min(thick, width);
        int acrossY = Math.min(thick, height);
        int acrossZ = Math.min(thick, depth);
        return switch (face) {
            case "front" -> new Cut(new int[] {0, 0, depth - acrossZ}, new int[] {width, height, acrossZ}, 0);
            case "back" -> new Cut(new int[] {0, 0, 0}, new int[] {width, height, acrossZ}, 2);
            case "left" -> new Cut(new int[] {0, 0, 0}, new int[] {acrossX, height, depth}, 3);
            case "right" -> new Cut(new int[] {width - acrossX, 0, 0}, new int[] {acrossX, height, depth}, 1);
            case "top" -> new Cut(new int[] {0, height - acrossY, 0}, new int[] {width, acrossY, depth}, 0);
            default -> new Cut(new int[] {0, 0, 0}, new int[] {width, acrossY, depth}, 0);
        };
    }

    private static Scope face(Frame frame, Vec3i size, boolean mirrored, String name, int thick) {
        Cut cut = cutOf(size, name, thick);
        return turned(frame, cut.low(), cut.size(), cut.turns(), name, name, mirrored);
    }

    private static Scope meet(Frame frame, Vec3i size, boolean mirrored, int thick, String name, String... faces) {
        int[] low = {0, 0, 0};
        int[] high = {size.getX(), size.getY(), size.getZ()};
        for (String face : faces) {
            Cut cut = cutOf(size, face, thick);
            for (int axis = 0; axis < 3; axis++) {
                low[axis] = Math.max(low[axis], cut.low()[axis]);
                high[axis] = Math.min(high[axis], cut.low()[axis] + cut.size()[axis]);
            }
        }
        int[] measured = {high[0] - low[0], high[1] - low[1], high[2] - low[2]};
        return turned(frame, low, measured, cutOf(size, faces[0], thick).turns(), name, "", mirrored);
    }

    private static Scope turned(Frame frame, int[] low, int[] size, int turns, String name, String face,
            boolean mirrored) {
        Frame at = frame.moved(low[0], low[1], low[2]);
        int width = size[0];
        int height = size[1];
        int depth = size[2];
        for (int turn = 0; turn < Math.floorMod(turns, 4); turn++) {
            at = at.moved(0, 0, depth).quartered(1);
            int wasWide = width;
            width = depth;
            depth = wasWide;
        }
        return Scope.of(at, width, height, depth, name, face, mirrored);
    }

    private static List<Scope> gable(Solid.Gable roof, String selector, int thick, boolean mirrored)
            throws EvalException {
        boolean alongX = roof.ridge() == 0;
        String low = alongX ? "slope-back" : "slope-left";
        String high = alongX ? "slope-front" : "slope-right";
        return switch (selector) {
            case "slopes", "all" -> List.of(
                slope(roof.frame(), roof.size(), roof.rise(), roof.run(), roof.thickness(), thick, alongX, false,
                    roof.across() / 2.0D, low, mirrored),
                slope(roof.frame(), roof.size(), roof.rise(), roof.run(), roof.thickness(), thick, alongX, true,
                    roof.across() / 2.0D, high, mirrored));
            case "ridge" -> List.of(ridge(roof.frame(), roof.size(), alongX, roof.across(),
                roof.thickness() + (int) Math.ceil(roof.peak()), mirrored));
            default -> {
                if (selector.equals(low) || selector.equals(high)) {
                    yield List.of(slope(roof.frame(), roof.size(), roof.rise(), roof.run(), roof.thickness(),
                        thick, alongX, selector.equals(high), roof.across() / 2.0D, selector, mirrored));
                }
                throw Starlark.errorf("a gable offers 'slopes', '%s', '%s' or 'ridge', not '%s'", low, high,
                    selector);
            }
        };
    }

    private static List<Scope> shed(Solid.Shed roof, String selector, int thick, boolean mirrored)
            throws EvalException {
        return switch (selector) {
            case "slopes", "slope", "all" -> List.of(slope(roof.frame(), roof.size(), roof.rise(), roof.run(),
                roof.thickness(), thick, true, true, roof.size().getZ(), "slope", mirrored));
            case "ridge" -> List.of(ridge(roof.frame(), roof.size(), true, 1,
                roof.thickness() + (int) Math.ceil(roof.peak()), mirrored));
            default -> throw Starlark.errorf("a shed roof offers 'slopes' or 'ridge', not '%s'", selector);
        };
    }

    private static List<Scope> hip(Solid.Hip roof, String selector, int thick, boolean mirrored)
            throws EvalException {
        List<Scope> found = new ArrayList<>();
        if (!selector.equals("slopes") && !selector.equals("all")) {
            throw Starlark.errorf("a hip roof offers 'slopes', not '%s'", selector);
        }
        found.add(slope(roof.frame(), roof.size(), roof.rise(), roof.run(), roof.thickness(), thick, true, false,
            roof.size().getZ() / 2.0D, "slope-back", mirrored));
        found.add(slope(roof.frame(), roof.size(), roof.rise(), roof.run(), roof.thickness(), thick, true, true,
            roof.size().getZ() / 2.0D, "slope-front", mirrored));
        found.add(slope(roof.frame(), roof.size(), roof.rise(), roof.run(), roof.thickness(), thick, false, false,
            roof.size().getX() / 2.0D, "slope-left", mirrored));
        found.add(slope(roof.frame(), roof.size(), roof.rise(), roof.run(), roof.thickness(), thick, false, true,
            roof.size().getX() / 2.0D, "slope-right", mirrored));
        return found;
    }

    private static Scope slope(Frame frame, Vec3i size, int rise, int run, int courses, int thick,
            boolean ridgeAlongX, boolean far, double reach, String name, boolean mirrored) {
        double hyp = Math.hypot(rise, run);
        int width = ridgeAlongX ? size.getX() : size.getZ();
        int height = Math.max(1, (int) Math.round(reach * hyp / run));
        int eaveAt = far ? (ridgeAlongX ? size.getZ() : size.getX()) : 0;
        double away = far ? 1.0D : -1.0D;
        Vec3 up = ridgeAlongX
            ? local(frame, 0.0D, rise / hyp, -away * run / hyp)
            : local(frame, -away * run / hyp, rise / hyp, 0.0D);
        Vec3 out = ridgeAlongX
            ? local(frame, 0.0D, run / hyp, away * rise / hyp)
            : local(frame, away * rise / hyp, run / hyp, 0.0D);
        Vec3 across = ridgeAlongX ? local(frame, far ? -1.0D : 1.0D, 0.0D, 0.0D)
            : local(frame, 0.0D, 0.0D, far ? 1.0D : -1.0D);
        double startX = ridgeAlongX ? (far ? size.getX() : 0) : eaveAt;
        double startZ = ridgeAlongX ? eaveAt : (far ? 0 : size.getZ());
        Vec3 eave = frame.at(startX, courses, startZ).subtract(out.scale(thick));
        return Scope.of(new Frame(eave, across, up, out), width, height, thick, name, "slope", mirrored);
    }

    private static Scope ridge(Frame frame, Vec3i size, boolean alongX, int across, int top, boolean mirrored) {
        int thin = across <= 1 ? 1 : across - 2 * ((across - 1) / 2);
        int start = (across - thin) / 2;
        Frame at = alongX ? frame.moved(0, top - 1, start) : frame.moved(start, top - 1, 0);
        return alongX
            ? Scope.of(at, size.getX(), 1, thin, "ridge", "top", mirrored)
            : Scope.of(at, thin, 1, size.getZ(), "ridge", "top", mirrored);
    }

    private static Vec3 local(Frame frame, double x, double y, double z) {
        return frame.right().scale(x).add(frame.up().scale(y)).add(frame.forward().scale(z));
    }

    private static List<Scope> prism(Solid.Prism plan, String selector, int thick, boolean mirrored)
            throws EvalException {
        int height = plan.maxY() - plan.minY() + 1;
        if (selector.equals("top") || selector.equals("bottom")) {
            boolean top = selector.equals("top");
            int deep = Math.min(thick, height);
            BoundingBox over = plan.bounds();
            return List.of(Scope.over(new BoundingBox(over.minX(),
                top ? plan.maxY() - deep + 1 : plan.minY(), over.minZ(), over.maxX(),
                top ? plan.maxY() : plan.minY() + deep - 1, over.maxZ())).named(selector));
        }
        if (!selector.equals("sides") && !selector.equals("all")) {
            throw Starlark.errorf("a stood-up outline offers 'sides', 'top' or 'bottom', not '%s'", selector);
        }
        List<double[]> outline = plan.outline();
        List<Scope> found = new ArrayList<>();
        for (int at = 0; at < outline.size(); at++) {
            double[] a = outline.get(at);
            double[] b = outline.get((at + 1) % outline.size());
            double dx = b[0] - a[0];
            double dz = b[1] - a[1];
            double length = Math.hypot(dx, dz);
            if (length < 1.0E-6) {
                continue;
            }
            double[] out = outward(plan, a, b, dx / length, dz / length);
            Vec3 forward = new Vec3(out[0], 0.0D, out[1]);
            Vec3 across = new Vec3(-out[1], 0.0D, out[0]);
            double[] start = (dx * across.x + dz * across.z) >= 0 ? a : b;
            Vec3 corner = new Vec3(start[0], plan.minY(), start[1]).subtract(forward.scale(thick));
            found.add(Scope.of(new Frame(corner, across, new Vec3(0, 1, 0), forward),
                Math.max(1, (int) Math.round(length)), height, thick, "side", "side", mirrored));
        }
        if (found.isEmpty()) {
            throw Starlark.errorf("this outline has no side long enough to build on");
        }
        return found;
    }

    private static double[] outward(Solid.Prism plan, double[] a, double[] b, double ux, double uz) {
        double midX = (a[0] + b[0]) / 2.0D;
        double midZ = (a[1] + b[1]) / 2.0D;
        double nx = uz;
        double nz = -ux;
        return plan.holds(midX + nx * 0.5D, plan.minY() + 0.5D, midZ + nz * 0.5D)
            ? new double[] {-nx, -nz}
            : new double[] {nx, nz};
    }
}
