package io.github.izakyl.folkways.plugins.build.draft;

import java.util.ArrayList;
import java.util.List;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkValue;

@StarlarkBuiltin(name = "outline", doc = "A plan drawn on the ground, which can be drawn in or out as a whole.")
public record Outline(List<double[]> corners) implements StarlarkValue {

    private static final double NEARLY = 1.0E-9;

    public Outline {
        List<double[]> kept = new ArrayList<>(corners.size());
        for (double[] corner : corners) {
            kept.add(new double[] {corner[0], corner[1]});
        }
        corners = List.copyOf(kept);
        if (corners.size() < 3) {
            throw new IllegalArgumentException("an outline has at least three corners");
        }
        if (Math.abs(area(corners)) < NEARLY) {
            throw new IllegalArgumentException("an outline's corners must not all lie on one line");
        }
    }

    static Outline around(Scope scope) {
        List<double[]> corners = new ArrayList<>(4);
        int width = scope.size().getX();
        int depth = scope.size().getZ();
        for (int[] corner : new int[][] {{0, 0}, {width, 0}, {width, depth}, {0, depth}}) {
            var at = scope.frame().at(corner[0], 0, corner[1]);
            corners.add(new double[] {at.x, at.z});
        }
        return new Outline(corners);
    }

    @StarlarkMethod(
        name = "offset",
        doc = "The same plan drawn out by so many cells, or in when the number is below zero. Every side moves"
            + " the same distance and the corners are carried along, so a courtyard is the plan taken from its"
            + " own offset.",
        parameters = {@Param(name = "by")})
    public Outline offset(Object by) throws EvalException {
        double cells = Scope.number(by, "an offset");
        if (Math.abs(cells) < NEARLY) {
            return this;
        }
        double sign = area(corners) > 0 ? 1.0D : -1.0D;
        int count = corners.size();
        List<double[]> lines = new ArrayList<>(count);
        for (int at = 0; at < count; at++) {
            double[] a = corners.get(at);
            double[] b = corners.get((at + 1) % count);
            double dx = b[0] - a[0];
            double dz = b[1] - a[1];
            double length = Math.hypot(dx, dz);
            if (length < NEARLY) {
                throw Starlark.errorf("this outline doubles back on a corner, so it cannot be drawn in or out");
            }
            double ux = dx / length;
            double uz = dz / length;
            lines.add(new double[] {a[0] + uz * sign * cells, a[1] - ux * sign * cells, ux, uz});
        }
        List<double[]> made = new ArrayList<>(count);
        for (int at = 0; at < count; at++) {
            double[] before = lines.get((at - 1 + count) % count);
            double[] now = lines.get(at);
            made.add(cross(before, now, corners.get(at), sign * cells));
        }
        for (int at = 0; at < count; at++) {
            if (!runsOn(corners.get(at), corners.get((at + 1) % count),
                    made.get(at), made.get((at + 1) % count))) {
                throw Starlark.errorf("drawing this outline by %s turns one of its sides back on itself; there"
                    + " is not that much of it to give", Starlark.repr(by));
            }
        }
        return new Outline(made);
    }

    private static boolean runsOn(double[] wasA, double[] wasB, double[] nowA, double[] nowB) {
        return (wasB[0] - wasA[0]) * (nowB[0] - nowA[0]) + (wasB[1] - wasA[1]) * (nowB[1] - nowA[1]) > NEARLY;
    }

    private static double[] cross(double[] before, double[] now, double[] corner, double moved) {
        double denominator = before[2] * now[3] - before[3] * now[2];
        if (Math.abs(denominator) < 1.0E-6) {
            return new double[] {corner[0] + now[3] * moved, corner[1] - now[2] * moved};
        }
        double t = ((now[0] - before[0]) * now[3] - (now[1] - before[1]) * now[2]) / denominator;
        return new double[] {before[0] + before[2] * t, before[1] + before[3] * t};
    }

    @StarlarkMethod(name = "corners", doc = "The corners, each as a list of two numbers: x and z.",
        structField = true)
    public StarlarkList<StarlarkList<StarlarkFloat>> written() {
        List<StarlarkList<StarlarkFloat>> all = new ArrayList<>(corners.size());
        for (double[] corner : corners) {
            all.add(StarlarkList.immutableOf(StarlarkFloat.of(corner[0]), StarlarkFloat.of(corner[1])));
        }
        return StarlarkList.immutableCopyOf(all);
    }

    @StarlarkMethod(name = "count", doc = "How many corners the plan has.", structField = true)
    public StarlarkInt count() {
        return StarlarkInt.of(corners.size());
    }

    @StarlarkMethod(name = "area", doc = "How many cells of ground the plan covers.", structField = true)
    public StarlarkFloat spread() {
        return StarlarkFloat.of(Math.abs(area(corners)));
    }

    private static double area(List<double[]> corners) {
        double twice = 0.0D;
        for (int at = 0, before = corners.size() - 1; at < corners.size(); before = at++) {
            twice += corners.get(before)[0] * corners.get(at)[1] - corners.get(at)[0] * corners.get(before)[1];
        }
        return twice / 2.0D;
    }

    @Override
    public boolean isImmutable() {
        return true;
    }

    @Override
    public void repr(Printer printer) {
        printer.append("outline(" + corners.size() + " corners)");
    }
}
