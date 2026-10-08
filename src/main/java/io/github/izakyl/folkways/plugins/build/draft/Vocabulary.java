package io.github.izakyl.folkways.plugins.build.draft;

import com.google.common.collect.ImmutableMap;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.front.api.Schema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

@StarlarkBuiltin(name = "folkways", doc = "What a folkways pattern can say.")
public final class Vocabulary implements StarlarkValue {

    private static final Vocabulary INSTANCE = new Vocabulary();

    private static final int MOST_WITHIN = 32;

    private Vocabulary() {
    }

    static ImmutableMap<String, Object> predeclared() {
        ImmutableMap.Builder<String, Object> environment = ImmutableMap.builder();
        Starlark.addMethods(environment, INSTANCE);
        return environment.build();
    }

    @StarlarkBuiltin(name = "shape", doc = "A region of a building's space.")
    public record Shape(Solid solid) implements StarlarkValue {

        @StarlarkMethod(name = "bounds", doc = "The scope this region was cut to, keeping the way it faces;"
            + " for a region with no frame of its own, the smallest box holding it, facing north.",
            structField = true)
        public Scope bounds() {
            return Facets.roomOf(solid);
        }

        @Override
        public boolean isImmutable() {
            return true;
        }
    }

    @StarlarkBuiltin(name = "part", doc = "One named region of a building, with what it is made of.")
    public record Made(Massing.Part part) implements StarlarkValue {

        @StarlarkMethod(name = "region", doc = "The region this part covers, to go on building from.",
            structField = true)
        public Shape region() {
            return new Shape(part.solid());
        }

        @StarlarkMethod(name = "role", doc = "What this part is called.", structField = true)
        public String roleName() {
            return part.role();
        }

        @StarlarkMethod(name = "labels", doc = "Every name this part is known by, its role first.",
            structField = true)
        public StarlarkList<String> labelNames() {
            List<String> all = new ArrayList<>();
            all.add(part.role());
            all.addAll(part.labels());
            return StarlarkList.immutableCopyOf(all);
        }

        @Override
        public boolean isImmutable() {
            return true;
        }

        @Override
        public void repr(Printer printer) {
            printer.append("<part " + part.role() + ">");
        }
    }

    @StarlarkBuiltin(name = "joint", doc = "Two laid blocks the builders join once both stand.")
    public record Joined(Joint joint) implements StarlarkValue {

        @Override
        public boolean isImmutable() {
            return true;
        }
    }

    @StarlarkBuiltin(name = "knob", doc = "One thing the player fills in.")
    public record Knob(Schema.Setting setting) implements StarlarkValue {
    }

    @StarlarkMethod(name = "point", doc = "A cell, counted from the site's anchor.",
        parameters = {@Param(name = "x"), @Param(name = "y"), @Param(name = "z")})
    public Point point(StarlarkInt x, StarlarkInt y, StarlarkInt z) throws EvalException {
        return new Point(x.toInt("x"), y.toInt("y"), z.toInt("z"));
    }

    @StarlarkMethod(
        name = "split",
        doc = "Divide a scope along its own x, y or z. A share is '3' cells, '0.5r' of the span, '~1' of what is"
            + " left by weight, '4*' or '~4*' repeated, a piece (its own size), or repeat(...). What no share"
            + " claims is an error unless rest says 'drop', 'center' or 'end'. symmetric keeps the halves"
            + " mirror images. names names each share's scopes. snap pulls cuts within tolerance onto planes an"
            + " earlier phase emitted under that label; emit leaves this split's cuts for later phases.",
        parameters = {
            @Param(name = "scope"), @Param(name = "axis"), @Param(name = "shares"),
            @Param(name = "rest", defaultValue = "'error'", named = true, positional = false),
            @Param(name = "symmetric", defaultValue = "False", named = true, positional = false),
            @Param(name = "names", defaultValue = "None", named = true, positional = false),
            @Param(name = "snap", defaultValue = "None", named = true, positional = false),
            @Param(name = "tolerance", defaultValue = "1", named = true, positional = false),
            @Param(name = "emit", defaultValue = "None", named = true, positional = false)},
        useStarlarkThread = true)
    public StarlarkList<Scope> split(Scope scope, String axis, Sequence<?> shares, String rest, boolean symmetric,
            Object names, Object snap, StarlarkInt tolerance, Object emit, StarlarkThread thread)
            throws EvalException {
        int along = scope.along(axis);
        List<Splits.Share> parsed = new ArrayList<>();
        for (Object share : shares) {
            parsed.add(Splits.parse(share, along));
        }
        List<String> naming = names == Starlark.NONE ? null : Rule.strings(names);
        Splits.Layout layout = Splits.resolve(scope.span(along), parsed, naming, Splits.rest(rest), symmetric);

        List<Splits.Cut> cuts = layout.cuts();
        int[] starts = new int[cuts.size() + 1];
        int cursor = layout.lead();
        for (int index = 0; index < cuts.size(); index++) {
            starts[index] = cursor;
            cursor += cuts.get(index).size();
        }
        starts[cuts.size()] = cursor;

        if (snap != Starlark.NONE || emit != Starlark.NONE) {
            Derivation drawing = Derivation.of(thread);
            Direction way = squareAxis(scope, along);
            if (snap != Starlark.NONE) {
                List<Integer> planes = drawing.planes(Starlark.str(snap), way.getAxis());
                int reach = tolerance.toInt("tolerance");
                for (int index = 1; index < cuts.size(); index++) {
                    int boundary = boundary(scope, along, way, starts[index]);
                    Integer nearest = null;
                    for (int plane : planes) {
                        if (Math.abs(plane - boundary) <= reach
                            && (nearest == null || Math.abs(plane - boundary) < Math.abs(nearest - boundary))) {
                            nearest = plane;
                        }
                    }
                    if (nearest != null) {
                        int moved = offsetOf(scope, along, way, nearest);
                        if (moved > starts[index - 1] && moved < starts[index + 1]) {
                            starts[index] = moved;
                        }
                    }
                }
            }
            if (emit != Starlark.NONE) {
                for (int index = 1; index < cuts.size(); index++) {
                    drawing.emit(Starlark.str(emit), way.getAxis(), boundary(scope, along, way, starts[index]));
                }
            }
        }

        List<Scope> parts = new ArrayList<>(cuts.size());
        for (int index = 0; index < cuts.size(); index++) {
            Scope slice = scope.slice(along, starts[index], starts[index + 1] - starts[index]);
            String name = cuts.get(index).name();
            parts.add(name.isEmpty() ? slice : slice.named(name));
        }
        return StarlarkList.immutableCopyOf(parts);
    }

    private static Direction squareAxis(Scope scope, int along) throws EvalException {
        if (!scope.frame().square()) {
            throw Starlark.errorf("snapping and emitting need a scope square to the world, and this one lies"
                + " at an angle");
        }
        return Frame.nearest(scope.axis(along));
    }

    private static int boundary(Scope scope, int along, Direction way, int offset) {
        return (int) Math.round(component(scope.pointAt(along, offset), way.getAxis()));
    }

    private static int offsetOf(Scope scope, int along, Direction way, int boundary) {
        double start = component(scope.pointAt(along, 0), way.getAxis());
        return (int) Math.round((boundary - start) * way.getAxisDirection().getStep());
    }

    private static double component(Vec3 point, Direction.Axis axis) {
        return switch (axis) {
            case X -> point.x;
            case Y -> point.y;
            case Z -> point.z;
        };
    }

    @StarlarkMethod(
        name = "repeat",
        doc = "A share that tiles what a split leaves over with whole units: '4', '~4' or a piece, with gap '1',"
            + " '~1' or None between them. edges puts a gap before the first unit and after the last too.",
        parameters = {
            @Param(name = "unit"),
            @Param(name = "gap", defaultValue = "None", named = true, positional = false),
            @Param(name = "edges", defaultValue = "True", named = true, positional = false)})
    public Splits.RepeatValue repeat(Object unit, Object gap, boolean edges) {
        return new Splits.RepeatValue(unit, gap, edges);
    }

    @StarlarkMethod(
        name = "comp",
        doc = "The faces of a scope or of a shape, depth cells thick. A scope or a box offers 'sides' (the"
            + " default), 'all' faces, one face by name, 'edges' (twelve, named like 'front-left') or 'corners'"
            + " (eight). A roof offers 'slopes' and 'ridge': a slope's own y runs up the slope, so what is split"
            + " or stamped on it lies along it. Something stood up from an outline offers 'sides', one per edge"
            + " of its plan, 'top' and 'bottom'.",
        parameters = {
            @Param(name = "region"),
            @Param(name = "faces", defaultValue = "'sides'", named = true),
            @Param(name = "depth", defaultValue = "1", named = true, positional = false)})
    public StarlarkList<Scope> comp(Object region, String faces, StarlarkInt depth) throws EvalException {
        int thick = depth.toInt("depth");
        if (thick < 1) {
            throw Starlark.errorf("a comp is at least one cell deep, got %d", thick);
        }
        String selector = faces.toLowerCase(Locale.ROOT);
        if (region instanceof Scope scope) {
            return StarlarkList.immutableCopyOf(
                Facets.box(scope.frame(), scope.size(), scope.mirrored(), selector, thick));
        }
        return StarlarkList.immutableCopyOf(Facets.of(solidOf(region), selector, thick, false));
    }

    @StarlarkMethod(
        name = "outline",
        doc = "A plan drawn flat on the ground: the floor of a scope, or corners written out as [x, z] pairs on"
            + " a scope's own grid lines, (0, 0) being the corner its axes start at. An outline can be drawn in"
            + " or out as a whole with plan.offset(cells), which is how a courtyard or a set-back storey is cut.",
        parameters = {
            @Param(name = "scope"),
            @Param(name = "corners", defaultValue = "None", named = true, positional = false)})
    public Outline outline(Scope scope, Object corners) throws EvalException {
        if (corners == Starlark.NONE) {
            return Outline.around(scope);
        }
        List<double[]> written = cornersOf(scope,
            sequenceOf(corners, "an outline's corners are a list of [x, z] pairs"));
        return shaped(() -> new Outline(written));
    }

    @StarlarkMethod(name = "box", doc = "The scope, filled.", parameters = {@Param(name = "scope")})
    public Shape box(Scope scope) {
        return new Shape(scope.solid());
    }

    @StarlarkMethod(
        name = "hollow",
        doc = "The scope with its interior taken out, leaving four walls. Open top and bottom.",
        parameters = {
            @Param(name = "scope"),
            @Param(name = "thickness", defaultValue = "1", named = true, positional = false)})
    public Shape hollow(Scope scope, StarlarkInt thickness) throws EvalException {
        int walls = thickness.toInt("thickness");
        return new Shape(shaped(() -> new Solid.Hollow(scope.frame(), scope.size(), walls)));
    }

    @StarlarkMethod(
        name = "gable",
        doc = "A pitched roof over the scope, its ridge along the scope's own x or z. A pitch is 1:1, 1:2 or 2:1.",
        parameters = {
            @Param(name = "scope"),
            @Param(name = "ridge", defaultValue = "'x'", named = true, positional = false),
            @Param(name = "rise", defaultValue = "1", named = true, positional = false),
            @Param(name = "run", defaultValue = "1", named = true, positional = false),
            @Param(name = "thickness", defaultValue = "1", named = true, positional = false)})
    public Shape gable(Scope scope, String ridge, StarlarkInt rise, StarlarkInt run, StarlarkInt thickness)
            throws EvalException {
        int along = scope.along(ridge);
        if (along == 1) {
            throw Starlark.errorf("a ridge runs along the scope's own x or z, not its y");
        }
        int up = rise.toInt("rise");
        int across = run.toInt("run");
        int courses = thickness.toInt("thickness");
        return new Shape(shaped(() -> new Solid.Gable(scope.frame(), scope.size(), along, up, across, courses)));
    }

    @StarlarkMethod(
        name = "shed",
        doc = "A single-slope roof over the scope, lowest at its front and climbing toward its back.",
        parameters = {
            @Param(name = "scope"),
            @Param(name = "rise", defaultValue = "1", named = true, positional = false),
            @Param(name = "run", defaultValue = "1", named = true, positional = false),
            @Param(name = "thickness", defaultValue = "1", named = true, positional = false)})
    public Shape shed(Scope scope, StarlarkInt rise, StarlarkInt run, StarlarkInt thickness) throws EvalException {
        int up = rise.toInt("rise");
        int across = run.toInt("run");
        int courses = thickness.toInt("thickness");
        return new Shape(shaped(() -> new Solid.Shed(scope.frame(), scope.size(), up, across, courses)));
    }

    @StarlarkMethod(
        name = "hip",
        doc = "A roof over the scope sloping up from all four eaves.",
        parameters = {
            @Param(name = "scope"),
            @Param(name = "rise", defaultValue = "1", named = true, positional = false),
            @Param(name = "run", defaultValue = "1", named = true, positional = false),
            @Param(name = "thickness", defaultValue = "1", named = true, positional = false)})
    public Shape hip(Scope scope, StarlarkInt rise, StarlarkInt run, StarlarkInt thickness) throws EvalException {
        int up = rise.toInt("rise");
        int across = run.toInt("run");
        int courses = thickness.toInt("thickness");
        return new Shape(shaped(() -> new Solid.Hip(scope.frame(), scope.size(), up, across, courses)));
    }

    @StarlarkMethod(
        name = "cylinder",
        doc = "An elliptic cylinder filling the scope, standing along its own x, y (the default) or z.",
        parameters = {
            @Param(name = "scope"),
            @Param(name = "axis", defaultValue = "'y'", named = true, positional = false)})
    public Shape cylinder(Scope scope, String axis) throws EvalException {
        int along = scope.along(axis);
        return new Shape(shaped(() -> new Solid.Cylinder(scope.frame(), scope.size(), along, false)));
    }

    @StarlarkMethod(name = "cone", doc = "A cone standing in the scope, its point at the top.",
        parameters = {@Param(name = "scope")})
    public Shape cone(Scope scope) throws EvalException {
        return new Shape(shaped(() -> new Solid.Cylinder(scope.frame(), scope.size(), 1, true)));
    }

    @StarlarkMethod(name = "dome", doc = "Half an ellipsoid standing on the scope's floor and filling it.",
        parameters = {@Param(name = "scope")})
    public Shape dome(Scope scope) throws EvalException {
        return new Shape(shaped(() -> new Solid.Ellipsoid(scope.frame(), scope.size(), true)));
    }

    @StarlarkMethod(name = "sphere", doc = "An ellipsoid filling the scope.", parameters = {@Param(name = "scope")})
    public Shape sphere(Scope scope) throws EvalException {
        return new Shape(shaped(() -> new Solid.Ellipsoid(scope.frame(), scope.size(), false)));
    }

    @StarlarkMethod(
        name = "extrude",
        doc = "A plan stood up: an outline, or a list of [x, z] corners on the scope's own grid lines, (0, 0)"
            + " being the corner its axes start at. It rises straight up from the scope's floor by height cells"
            + " (the scope's own height when 0).",
        parameters = {
            @Param(name = "scope"), @Param(name = "plan"),
            @Param(name = "height", defaultValue = "0", named = true, positional = false)})
    public Shape extrude(Scope scope, Object plan, StarlarkInt height) throws EvalException {
        List<double[]> corners = plan instanceof Outline drawn ? drawn.corners()
            : cornersOf(scope, sequenceOf(plan, "a plan is an outline or a list of [x, z] corners"));
        int tall = height.toInt("height");
        int minY = scope.bounds().minY();
        int maxY = minY + (tall == 0 ? scope.size().getY() : tall) - 1;
        return new Shape(shaped(() -> new Solid.Prism(corners, minY, maxY)));
    }

    private static List<double[]> cornersOf(Scope scope, Sequence<?> written) throws EvalException {
        List<double[]> corners = new ArrayList<>();
        for (Object corner : written) {
            if (!(corner instanceof Sequence<?> pair) || pair.size() != 2) {
                throw Starlark.errorf("an outline corner is [x, z], got %s", Starlark.repr(corner));
            }
            Vec3 at = scope.frame().at(Starlark.toInt(pair.get(0), "x"), 0, Starlark.toInt(pair.get(1), "z"));
            corners.add(new double[] {at.x, at.z});
        }
        return corners;
    }

    @StarlarkMethod(
        name = "sweep",
        doc = "A band following a line of points (a site.path, or a list of points or [x, y, z] triples, whose y"
            + " may fall between cells): width cells across and height tall, shifted sideways by offset (to the"
            + " right of the way the line runs, when positive). With y it lies level at that height; without, it"
            + " climbs with the points.",
        parameters = {
            @Param(name = "line"),
            @Param(name = "width", defaultValue = "1", named = true, positional = false),
            @Param(name = "height", defaultValue = "1", named = true, positional = false),
            @Param(name = "y", defaultValue = "None", named = true, positional = false),
            @Param(name = "offset", defaultValue = "0", named = true, positional = false)})
    public Shape sweep(Object line, StarlarkInt width, StarlarkInt height, Object y, StarlarkInt offset)
            throws EvalException {
        List<double[]> centres;
        if (line instanceof DraftSite.PathLine path) {
            centres = path.centres();
        } else if (line instanceof Sequence<?> points) {
            centres = new ArrayList<>();
            for (Object point : points) {
                if (point instanceof Point cell) {
                    centres.add(new double[] {cell.x() + 0.5D, cell.y(), cell.z() + 0.5D});
                } else if (point instanceof Sequence<?> triple && triple.size() == 3) {
                    centres.add(new double[] {number(triple.get(0), "x") + 0.5D, number(triple.get(1), "y"),
                        number(triple.get(2), "z") + 0.5D});
                } else {
                    throw Starlark.errorf("a sweep follows points or [x, y, z] triples, got %s", Starlark.repr(point));
                }
            }
        } else {
            throw Starlark.errorf("a sweep follows a path or a list of points, got %s", Starlark.type(line));
        }
        int wide = width.toInt("width");
        int tall = height.toInt("height");
        int shift = offset.toInt("offset");
        boolean level = y != Starlark.NONE;
        int base = level ? Starlark.toInt(y, "y") : 0;
        return new Shape(shaped(() -> new Solid.Sweep(centres, wide, tall, base, !level, shift)));
    }

    @StarlarkMethod(
        name = "cells",
        doc = "Whole cells, as a region: a list of points or [x, y, z] triples. A pattern that works out its own"
            + " cuts and fills column by column hands them over this way.",
        parameters = {@Param(name = "points")})
    public Shape cells(Sequence<?> points) throws EvalException {
        Set<BlockPos> filled = new HashSet<>();
        for (Object point : points) {
            if (point instanceof Point cell) {
                filled.add(cell.pos());
            } else if (point instanceof Sequence<?> triple && triple.size() == 3) {
                filled.add(new BlockPos(Starlark.toInt(triple.get(0), "x"), Starlark.toInt(triple.get(1), "y"),
                    Starlark.toInt(triple.get(2), "z")));
            } else {
                throw Starlark.errorf("cells are points or [x, y, z] triples, got %s", Starlark.repr(point));
            }
        }
        if (filled.isEmpty()) {
            throw Starlark.errorf("cells needs at least one cell");
        }
        return new Shape(new Solid.Cells(filled));
    }

    private static double number(Object value, String what) throws EvalException {
        if (value instanceof StarlarkInt whole) {
            return whole.toInt(what);
        }
        if (value instanceof StarlarkFloat part) {
            return part.toDouble();
        }
        throw Starlark.errorf("%s is a number, got %s", what, Starlark.type(value));
    }

    @StarlarkMethod(
        name = "under",
        doc = "Everything under a shape (a roof, usually) down to a floor: a scope's own floor or a height.",
        parameters = {@Param(name = "shape", named = true), @Param(name = "floor", named = true)})
    public Shape under(Object shape, Object floor) throws EvalException {
        Solid over = solidOf(shape);
        int floorY = floor instanceof Scope scope ? scope.bounds().minY() : Starlark.toInt(floor, "floor");
        return new Shape(shaped(() -> new Solid.Under(over, floorY)));
    }

    @StarlarkMethod(name = "subtract", doc = "The first region with the second taken out of it.",
        parameters = {@Param(name = "kept"), @Param(name = "taken")})
    public Shape subtract(Object kept, Object taken) throws EvalException {
        return new Shape(new Solid.Difference(solidOf(kept), solidOf(taken)));
    }

    @StarlarkMethod(name = "intersect", doc = "Only what two regions share. Regions that share nothing are an"
        + " error; trim answers None instead.",
        parameters = {@Param(name = "one"), @Param(name = "other")})
    public Shape intersect(Object one, Object other) throws EvalException {
        Solid first = solidOf(one);
        Solid second = solidOf(other);
        return new Shape(shaped(() -> new Solid.Intersection(first, second)));
    }

    @StarlarkMethod(
        name = "trim",
        doc = "A region cut back to what lies inside another, or None where nothing of it does. This is how a"
            + " piece of a rule that runs past what holds it is kept in bounds. Where the two boxes overlap by"
            + " more than one drawing's worth of cells it is taken on trust that something is shared.",
        parameters = {@Param(name = "region"), @Param(name = "to", named = true)},
        allowReturnNones = true)
    public Shape trim(Object region, Object to) throws EvalException {
        Solid kept = solidOf(region);
        Solid limit = solidOf(to);
        if (!kept.bounds().intersects(limit.bounds())) {
            return null;
        }
        Solid cut = new Solid.Intersection(kept, limit);
        BoundingBox shared = cut.bounds();
        if (Lattice.volume(shared) > Lattice.MOST_SCANNED) {
            return new Shape(cut);
        }
        for (BlockPos cell : BlockPos.betweenClosed(shared.minX(), shared.minY(), shared.minZ(), shared.maxX(),
                shared.maxY(), shared.maxZ())) {
            if (Octants.of(cut, cell.getX(), cell.getY(), cell.getZ()) != Octants.NONE) {
                return new Shape(cut);
            }
        }
        return null;
    }

    @StarlarkMethod(name = "arched", doc = "Lift a shape continuously along a scope's z axis."
        + " End heights differ by end; rise is the extra height above their straight line.",
        parameters = {@Param(name = "shape"), @Param(name = "along"), @Param(name = "rise"),
            @Param(name = "end", defaultValue = "0", named = true, positional = false)})
    public Shape arched(Object shape, Scope along, StarlarkInt rise, StarlarkInt end) throws EvalException {
        Solid inner = solidOf(shape);
        int up = rise.toInt("rise");
        int dy = end.toInt("end");
        return new Shape(shaped(() -> new Solid.Arched(inner, along.frame(), along.size().getZ() - 1, up, dy)));
    }

    @StarlarkMethod(name = "vault", doc = "An arched opening filling a scope: straight sides up to its springing,"
        + " then a circular segment rise cells high across its depth. grow widens the curve only, so the"
        + " difference of a grown and a plain vault is the arch ring.",
        parameters = {@Param(name = "scope"), @Param(name = "rise"),
            @Param(name = "grow", defaultValue = "0", named = true, positional = false)})
    public Shape vault(Scope scope, StarlarkInt rise, StarlarkInt grow) throws EvalException {
        int up = rise.toInt("rise");
        int wider = grow.toInt("grow");
        return new Shape(shaped(() -> new Solid.Vault(scope.frame(), scope.size(), up, wider)));
    }

    @StarlarkMethod(name = "group", doc = "Several shapes or scopes as one.", parameters = {@Param(name = "shapes")})
    public Shape group(Sequence<?> shapes) throws EvalException {
        List<Solid> solids = new ArrayList<>();
        for (Object shape : shapes) {
            solids.add(solidOf(shape));
        }
        return new Shape(shaped(() -> new Solid.Group(solids)));
    }

    @StarlarkMethod(
        name = "part",
        doc = "One named region of a building and its palette: a list of blocks in priority, or mix(...). fit is"
            + " 'solid', 'stepped' (including matching family slabs), 'layered' (column-centre sampling"
            + " with half-block heights), or 'connected' (face-connected columns"
            + " confined to a fitted support role or label, retaining the requested top height). orient lays logs and pillars"
            + " 'along' the region's longest axis, or along 'x', 'y' or 'z'. over says which cells drawn before"
            + " it may take: 'all', 'empty', or a list of labels.",
        parameters = {
            @Param(name = "role"), @Param(name = "region"), @Param(name = "palette"),
            @Param(name = "fit", defaultValue = "'solid'", named = true, positional = false),
            @Param(name = "orient", defaultValue = "'auto'", named = true, positional = false),
            @Param(name = "labels", defaultValue = "[]", named = true, positional = false),
            @Param(name = "over", defaultValue = "'all'", named = true, positional = false),
            @Param(name = "support", defaultValue = "''", named = true, positional = false)})
    public Made part(String role, Object region, Object palette, String fit, String orient, Object labels,
            Object over, String support) throws EvalException {
        Fit how = switch (fit.toLowerCase(Locale.ROOT)) {
            case "solid" -> Fit.SOLID;
            case "stepped" -> Fit.STEPPED;
            case "connected" -> Fit.CONNECTED;
            case "layered" -> Fit.LAYERED;
            default -> throw Starlark.errorf(
                "a part is fitted 'solid', 'stepped', 'layered' or 'connected', not '%s' — to empty cells use clear()", fit);
        };
        Solid solid = solidOf(region);
        Direction.Axis axis = switch (orient.toLowerCase(Locale.ROOT)) {
            case "auto" -> null;
            case "along" -> longest(solid.bounds());
            case "x" -> Direction.Axis.X;
            case "y" -> Direction.Axis.Y;
            case "z" -> Direction.Axis.Z;
            default -> throw Starlark.errorf("orient is 'auto', 'along', 'x', 'y' or 'z', not '%s'", orient);
        };
        Materials materials = materialsOf(palette);
        return new Made(new Massing.Part(role, labelsOf(labels), solid,
            shaped(() -> new Skin.Fitted(materials, how, axis, support)), overOf(over), role));
    }

    @StarlarkMethod(
        name = "clear",
        doc = "A region that must end up empty. Declared after a part, this is how a doorway is cut.",
        parameters = {
            @Param(name = "role"), @Param(name = "region"),
            @Param(name = "labels", defaultValue = "[]", named = true, positional = false),
            @Param(name = "over", defaultValue = "'all'", named = true, positional = false)})
    public Made clear(String role, Object region, Object labels, Object over) throws EvalException {
        return new Made(new Massing.Part(role, labelsOf(labels), solidOf(region), Skin.cleared(), overOf(over), role));
    }

    @StarlarkMethod(
        name = "mix",
        doc = "A palette whose blocks are mixed by weight: {'minecraft:cobblestone': 7, 'minecraft:mossy_cobblestone': 3}.",
        parameters = {@Param(name = "weights")})
    public Materials mix(Dict<?, ?> weights) throws EvalException {
        List<ItemFilter> filters = new ArrayList<>();
        List<Integer> each = new ArrayList<>();
        for (Map.Entry<?, ?> entry : weights.entrySet()) {
            filters.add(filter(Starlark.str(entry.getKey())));
            each.add(Starlark.toInt(entry.getValue(), "weight"));
        }
        return shaped(() -> new Materials(filters, each));
    }

    @StarlarkMethod(
        name = "piece",
        doc = "A piece of building, as a character key and a stack of layers, bottom layer first. Each layer is a"
            + " list of rows, front row first. stretch names the columns ('x'), layers ('y') and rows ('z') that"
            + " may repeat or drop to fit a scope.",
        parameters = {
            @Param(name = "key", named = true),
            @Param(name = "layers", named = true),
            @Param(name = "stretch", defaultValue = "{}", named = true, positional = false)})
    public Piece piece(Dict<?, ?> key, Sequence<?> layers, Dict<?, ?> stretch) throws EvalException {
        Map<Character, Optional<BlockState>> written = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : key.entrySet()) {
            String mark = String.valueOf(entry.getKey());
            if (mark.length() != 1) {
                throw Starlark.errorf("a piece's key runs one character to one state, and '%s' is %d",
                    mark, mark.length());
            }
            Object value = entry.getValue();
            written.put(mark.charAt(0),
                value == null || value == Starlark.NONE ? Optional.empty() : Optional.of(stateOf(String.valueOf(value))));
        }

        int height = layers.size();
        if (height == 0) {
            throw Starlark.errorf("a piece has at least one layer");
        }
        int depth = -1;
        int wide = -1;
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (int y = 0; y < height; y++) {
            if (!(layers.get(y) instanceof Sequence<?> rows)) {
                throw Starlark.errorf("a layer is a list of rows, and layer %d is %s", y, Starlark.type(layers.get(y)));
            }
            if (depth < 0) {
                depth = rows.size();
            } else if (rows.size() != depth) {
                throw Starlark.errorf("layer %d has %d rows and layer 0 has %d", y, rows.size(), depth);
            }
            for (int row = 0; row < depth; row++) {
                String line = String.valueOf(rows.get(row));
                if (wide < 0) {
                    wide = line.length();
                } else if (line.length() != wide) {
                    throw Starlark.errorf("row %d of layer %d is %d characters and the first is %d",
                        row, y, line.length(), wide);
                }
                for (int x = 0; x < wide; x++) {
                    char mark = line.charAt(x);
                    Optional<BlockState> state = written.get(mark);
                    if (state == null) {
                        throw Starlark.errorf("'%c' is not in this piece's key", mark);
                    }
                    if (state.isPresent()) {
                        cells.put(new BlockPos(x, y, depth - 1 - row), state.get());
                    }
                }
            }
        }
        if (cells.isEmpty()) {
            throw Starlark.errorf("this piece holds no cell at all");
        }
        Vec3i size = new Vec3i(wide, height, depth);
        List<Set<Integer>> slices = slicesOf(stretch, wide, height, depth);
        return shaped(() -> new Piece(cells, size, slices));
    }

    @StarlarkMethod(
        name = "template",
        doc = "A piece read from a structure file a data pack ships, as 'folkways:cottage_door'. Its front row"
            + " is the one nearest the structure's own north, so it stamps the way a piece written out does.",
        parameters = {
            @Param(name = "id"),
            @Param(name = "stretch", defaultValue = "{}", named = true, positional = false)})
    public Piece template(String id, Dict<?, ?> stretch) throws EvalException {
        Piece found = Templates.find(id(id))
            .orElseThrow(() -> Starlark.errorf("no piece called '%s' is loaded; a pattern reads pieces from"
                + " data/<namespace>/%s/<name>.nbt", id, Templates.FOLDER));
        Vec3i size = found.size();
        List<Set<Integer>> slices = slicesOf(stretch, size.getX(), size.getY(), size.getZ());
        return slices.equals(found.stretch()) ? found
            : shaped(() -> new Piece(found.cells(), size, slices));
    }

    private static List<Set<Integer>> slicesOf(Dict<?, ?> stretch, int width, int height, int depth)
            throws EvalException {
        List<Set<Integer>> slices = List.of(new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
        for (Map.Entry<?, ?> entry : stretch.entrySet()) {
            String axis = String.valueOf(entry.getKey());
            int along = "xyz".indexOf(axis);
            if (along < 0 || axis.length() != 1) {
                throw Starlark.errorf("a piece stretches along 'x', 'y' or 'z', not '%s'", entry.getKey());
            }
            if (!(entry.getValue() instanceof Sequence<?> indices)) {
                throw Starlark.errorf("stretch names a list of slices per axis, got %s",
                    Starlark.type(entry.getValue()));
            }
            for (Object index : indices) {
                int slice = Starlark.toInt(index, "slice");
                slices.get(along).add(along == 2 ? depth - 1 - slice : slice);
            }
        }
        return slices;
    }

    @StarlarkMethod(name = "variants", doc = "Pieces of one kind at several sizes; a stamp lays the largest that fits.",
        parameters = {@Param(name = "pieces")})
    public Piece.Variants variants(Sequence<?> pieces) throws EvalException {
        List<Piece> all = new ArrayList<>();
        for (Object each : pieces) {
            if (!(each instanceof Piece piece)) {
                throw Starlark.errorf("variants holds pieces, got %s", Starlark.type(each));
            }
            all.add(piece);
        }
        return shaped(() -> new Piece.Variants(all));
    }

    @StarlarkMethod(
        name = "stamp",
        doc = "Lay a piece (or the largest of some variants that fits) into a scope, turned and mirrored to its"
            + " frame. fit is 'start' (the default), 'center', 'end', 'exact', 'stretch' to grow or shrink its"
            + " stretch slices to the scope, or 'tile' to lay it over and over until the scope is full. A piece"
            + " that does not fit is an error unless overflow = 'clip'.",
        parameters = {
            @Param(name = "role"), @Param(name = "piece"), @Param(name = "scope"),
            @Param(name = "swap", defaultValue = "{}", named = true, positional = false),
            @Param(name = "fit", defaultValue = "'start'", named = true, positional = false),
            @Param(name = "parity", defaultValue = "'error'", named = true, positional = false),
            @Param(name = "overflow", defaultValue = "'error'", named = true, positional = false),
            @Param(name = "labels", defaultValue = "[]", named = true, positional = false),
            @Param(name = "over", defaultValue = "'all'", named = true, positional = false)})
    public Made stamp(String role, Object piece, Scope scope, Dict<?, ?> swap, String fit, String parity,
            String overflow, Object labels, Object over) throws EvalException {
        String how = fit.toLowerCase(Locale.ROOT);
        if (!List.of("start", "center", "end", "exact", "stretch", "tile").contains(how)) {
            throw Starlark.errorf("a stamp fits 'start', 'center', 'end', 'exact', 'stretch' or 'tile', not '%s'",
                fit);
        }
        boolean clip = switch (overflow.toLowerCase(Locale.ROOT)) {
            case "error" -> false;
            case "clip" -> true;
            default -> throw Starlark.errorf("overflow is 'error' or 'clip', not '%s'", overflow);
        };
        int[] room = {scope.size().getX(), scope.size().getY(), scope.size().getZ()};

        Piece chosen = choose(piece, room, how);
        if (how.equals("stretch")) {
            int[] target = new int[3];
            for (int axis = 0; axis < 3; axis++) {
                target[axis] = chosen.stretches(axis) ? Math.max(chosen.least(axis), room[axis]) : chosen.span(axis);
            }
            chosen = chosen.stretchedTo(target);
        }
        if (how.equals("tile")) {
            chosen = chosen.tiledTo(room);
        }

        int[] offset = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            int spare = room[axis] - chosen.span(axis);
            if (spare < 0 && !clip) {
                throw Starlark.errorf("a piece %dx%dx%d does not fit a scope %dx%dx%d; pass overflow = 'clip' to"
                    + " keep what does", chosen.span(0), chosen.span(1), chosen.span(2), room[0], room[1], room[2]);
            }
            if (how.equals("exact") && spare != 0) {
                throw Starlark.errorf("fit = 'exact' needs a piece %dx%dx%d and the scope is %dx%dx%d",
                    chosen.span(0), chosen.span(1), chosen.span(2), room[0], room[1], room[2]);
            }
            offset[axis] = switch (how) {
                case "end" -> spare;
                case "center" -> {
                    if (Math.floorMod(spare, 2) != 0) {
                        yield switch (parity.toLowerCase(Locale.ROOT)) {
                            case "low" -> Math.floorDiv(spare, 2);
                            case "high" -> Math.floorDiv(spare, 2) + 1;
                            default -> throw Starlark.errorf("centring a piece %d cells along %s in %d leaves one"
                                + " cell over; pass parity = 'low' or 'high'", chosen.span(axis), "xyz".charAt(axis),
                                room[axis]);
                        };
                    }
                    yield spare / 2;
                }
                default -> 0;
            };
        }
        Map<BlockPos, BlockState> local = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> cell : chosen.cells().entrySet()) {
            BlockPos at = cell.getKey();
            int x = at.getX() + offset[0];
            int y = at.getY() + offset[1];
            int z = at.getZ() + offset[2];
            if (x >= 0 && x < room[0] && y >= 0 && y < room[1] && z >= 0 && z < room[2]) {
                local.put(at, cell.getValue());
            }
        }
        if (local.isEmpty()) {
            throw Starlark.errorf("nothing of this piece lies inside the scope it was stamped into");
        }
        Map<BlockPos, BlockState> cells = new Piece(local, new Vec3i(chosen.span(0), chosen.span(1), chosen.span(2)))
            .laidInto(scope, offset);

        List<Swap> swaps = new ArrayList<>();
        for (Map.Entry<?, ?> entry : swap.entrySet()) {
            swaps.add(new Swap(filter(String.valueOf(entry.getKey())), materialsOf(entry.getValue())));
        }
        Set<BlockPos> filled = new LinkedHashSet<>(cells.keySet());
        return new Made(new Massing.Part(role, labelsOf(labels), shaped(() -> new Solid.Cells(filled)),
            shaped(() -> new Skin.Stamped(cells, swaps)), overOf(over), role));
    }

    @StarlarkMethod(
        name = "blocks",
        doc = "Exact block states at exact cells, counted from the site's anchor: a dict from point to a state"
            + " written like 'minecraft:oak_stairs[facing=east]', 'minecraft:air' for a cell that must end up"
            + " empty. swap rewrites blocks to a palette as a stamp does.",
        parameters = {
            @Param(name = "role"), @Param(name = "cells"),
            @Param(name = "swap", defaultValue = "{}", named = true, positional = false),
            @Param(name = "labels", defaultValue = "[]", named = true, positional = false),
            @Param(name = "over", defaultValue = "'all'", named = true, positional = false)},
        useStarlarkThread = true)
    public Made blocks(String role, Dict<?, ?> cells, Dict<?, ?> swap, Object labels, Object over,
            StarlarkThread thread) throws EvalException {
        Map<String, BlockState> read = new HashMap<>();
        Map<BlockPos, BlockState> laid = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : cells.entrySet()) {
            if (!(entry.getKey() instanceof Point at)) {
                throw Starlark.errorf("blocks maps points to states, and one key is %s",
                    Starlark.type(entry.getKey()));
            }
            String written = Starlark.str(entry.getValue());
            BlockState state = read.get(written);
            if (state == null) {
                state = stateOf(written, thread);
                read.put(written, state);
            }
            laid.put(at.pos(), state);
        }
        if (laid.isEmpty()) {
            throw Starlark.errorf("blocks was given no cell to lay");
        }
        List<Swap> swaps = new ArrayList<>();
        for (Map.Entry<?, ?> entry : swap.entrySet()) {
            swaps.add(new Swap(filter(String.valueOf(entry.getKey())), materialsOf(entry.getValue())));
        }
        Set<BlockPos> filled = new LinkedHashSet<>(laid.keySet());
        return new Made(new Massing.Part(role, labelsOf(labels), shaped(() -> new Solid.Cells(filled)),
            shaped(() -> new Skin.Stamped(laid, swaps)), overOf(over), role));
    }

    @StarlarkMethod(
        name = "joint",
        doc = "Join two track blocks this drawing lays with the curved or sloping track the game itself lays"
            + " between them, once both stand: a turn between two runs, or a climb between two level pieces"
            + " that point at each other. What the join costs is charged when it is built.",
        parameters = {@Param(name = "from"), @Param(name = "to")})
    public Joined joint(Point from, Point to) throws EvalException {
        return new Joined(shaped(() -> new Joint(from.pos(), to.pos())));
    }

    private static Piece choose(Object piece, int[] room, String how) throws EvalException {
        if (piece instanceof Piece one) {
            return one;
        }
        if (!(piece instanceof Piece.Variants variants)) {
            throw Starlark.errorf("a stamp lays a piece or variants, got %s", Starlark.type(piece));
        }
        Piece best = null;
        long bestVolume = -1;
        for (Piece candidate : variants.pieces()) {
            boolean fits = true;
            for (int axis = 0; axis < 3; axis++) {
                int least = how.equals("stretch") ? candidate.least(axis) : candidate.span(axis);
                fits &= how.equals("tile") || least <= room[axis];
            }
            long volume = (long) candidate.span(0) * candidate.span(1) * candidate.span(2);
            if (fits && volume > bestVolume) {
                best = candidate;
                bestVolume = volume;
            }
        }
        if (best == null) {
            throw Starlark.errorf("none of these %d variants fits a scope %dx%dx%d", variants.pieces().size(),
                room[0], room[1], room[2]);
        }
        return best;
    }

    private static final String AS_WRITTEN = "";

    @StarlarkMethod(
        name = "flag",
        doc = "A knob the player turns on or off.",
        parameters = {
            @Param(name = "key"),
            @Param(name = "default", defaultValue = "False", named = true, positional = false),
            @Param(name = "icon", defaultValue = "'minecraft:paper'", named = true, positional = false)})
    public Knob flag(String key, boolean byDefault, String icon) throws EvalException {
        return new Knob(new Schema.Setting.Flag(key, AS_WRITTEN, id(icon), byDefault));
    }

    @StarlarkMethod(
        name = "count",
        doc = "A knob the player dials between two bounds.",
        parameters = {
            @Param(name = "key"), @Param(name = "min"), @Param(name = "max"),
            @Param(name = "default", named = true, positional = false),
            @Param(name = "icon", defaultValue = "'minecraft:paper'", named = true, positional = false)})
    public Knob count(String key, StarlarkInt min, StarlarkInt max, StarlarkInt byDefault, String icon)
            throws EvalException {
        return new Knob(new Schema.Setting.Count(key, AS_WRITTEN, id(icon),
            min.toInt("min"), max.toInt("max"), byDefault.toInt("default")));
    }

    @StarlarkMethod(
        name = "choice",
        doc = "A knob the player picks one of a fixed set with. Options are plain words ('steep') or ids.",
        parameters = {
            @Param(name = "key"), @Param(name = "options"),
            @Param(name = "default", named = true, positional = false),
            @Param(name = "icon", defaultValue = "'minecraft:paper'", named = true, positional = false)})
    public Knob choice(String key, Sequence<?> options, String byDefault, String icon) throws EvalException {
        List<ResourceLocation> ids = new ArrayList<>();
        for (Object option : options) {
            ids.add(option(String.valueOf(option)));
        }
        ResourceLocation fallback = option(byDefault);
        if (!ids.contains(fallback)) {
            throw Starlark.errorf("choice '%s' defaults to '%s', which is not one of its options", key, byDefault);
        }
        return new Knob(new Schema.Setting.Choice(key, AS_WRITTEN, id(icon), ids, fallback));
    }

    @StarlarkMethod(
        name = "items",
        doc = "A slot the player fills with blocks — a palette a part can be built out of.",
        parameters = {
            @Param(name = "key"),
            @Param(name = "default", named = true, positional = false),
            @Param(name = "icon", defaultValue = "'minecraft:paper'", named = true, positional = false)})
    public Knob items(String key, Sequence<?> byDefault, String icon) throws EvalException {
        return new Knob(new Schema.Setting.Items(key, AS_WRITTEN, id(icon), filters(byDefault)));
    }

    @StarlarkMethod(
        name = "rule",
        doc = "A named rule. Calling it on a scope answers a node the drawing expands in the rule's phase (the"
            + " caller's own when None), as fn(site, scope, *args, **kwargs). label or labels name the scope for"
            + " queries in later phases; attrs are handed down to everything the rule draws, to be read with"
            + " attr(name); priority puts a rule before its fellows in the same phase, higher first. A call may"
            + " add to any of them with label =, labels =, name =, attrs = and priority =.",
        parameters = {
            @Param(name = "name"), @Param(name = "fn"),
            @Param(name = "phase", defaultValue = "None", named = true, positional = false),
            @Param(name = "priority", defaultValue = "0", named = true, positional = false),
            @Param(name = "label", defaultValue = "None", named = true, positional = false),
            @Param(name = "labels", defaultValue = "[]", named = true, positional = false),
            @Param(name = "attrs", defaultValue = "{}", named = true, positional = false)})
    public Rule rule(String name, StarlarkCallable fn, Object phase, StarlarkInt priority, Object label,
            Object labels, Dict<?, ?> attrs) throws EvalException {
        Set<String> named = new LinkedHashSet<>(labelsOf(labels));
        if (label != Starlark.NONE) {
            named.add(Starlark.str(label));
        }
        return new Rule(name, fn, phase == Starlark.NONE ? null : Starlark.str(phase), named,
            priority.toInt("priority"), attrsOf(attrs));
    }

    @StarlarkMethod(
        name = "attr",
        doc = "What a rule above this one handed down under this name, or a fallback where none did. Attributes"
            + " pass from a rule to everything it draws, however deep.",
        parameters = {
            @Param(name = "name"),
            @Param(name = "default", defaultValue = "None", named = true, positional = false)},
        useStarlarkThread = true,
        allowReturnNones = true)
    public Object attr(String name, Object fallback, StarlarkThread thread) throws EvalException {
        Object found = Derivation.of(thread).attribute(name);
        return found == null ? fallback : found;
    }

    @StarlarkMethod(
        name = "report",
        doc = "Add to a tally the drawing hands back when it is done — how many rooms it made, how much glass it"
            + " asks for. Numbers add up; anything else is counted once under what it says.",
        parameters = {
            @Param(name = "key"),
            @Param(name = "value", defaultValue = "1", named = true)},
        useStarlarkThread = true)
    public void report(String key, Object value, StarlarkThread thread) throws EvalException {
        Derivation.of(thread).report(key, value);
    }

    @StarlarkBuiltin(name = "grown", doc = "One round of a pattern that keeps growing.")
    public record Grown(Object parts, CompoundTag keep, boolean last) implements StarlarkValue {

        @Override
        public boolean isImmutable() {
            return true;
        }
    }

    @StarlarkMethod(
        name = "grown",
        doc = "What grow(site) answers for one round: the parts to build now, what to keep for the next round"
            + " (read back as site.kept), and whether this is the last round. keep = None carries site.kept"
            + " over unchanged. A grow that answers no parts at all has finished.",
        parameters = {
            @Param(name = "parts"),
            @Param(name = "keep", defaultValue = "None", named = true, positional = false),
            @Param(name = "last", defaultValue = "False", named = true, positional = false)},
        useStarlarkThread = true)
    public Grown grown(Object parts, Object keep, boolean last, StarlarkThread thread) throws EvalException {
        Derivation drawing = Derivation.of(thread);
        CompoundTag keeping;
        if (keep == Starlark.NONE) {
            keeping = drawing.commission().growth().kept();
        } else if (keep instanceof Dict<?, ?> written) {
            keeping = Kept.of(written);
        } else {
            throw Starlark.errorf("keep is a dict or None, got %s", Starlark.type(keep));
        }
        return new Grown(parts, keeping, last);
    }

    @StarlarkMethod(
        name = "occluded",
        doc = "Whether every cell of a scope is taken by what by names: 'terrain', 'parts', 'world', or labels."
            + " Only earlier phases count, and never what this rule or those above it drew.",
        parameters = {@Param(name = "scope", named = true), @Param(name = "by", named = true)},
        useStarlarkThread = true)
    public boolean occluded(Scope scope, Object by, StarlarkThread thread) throws EvalException {
        Derivation drawing = Derivation.of(thread);
        Derivation.By looking = Derivation.By.parse(by);
        BoundingBox box = scope.bounds();
        for (BlockPos cell : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                box.maxZ())) {
            if (scope.holds(cell.getX() + 0.5D, cell.getY() + 0.5D, cell.getZ() + 0.5D)
                && !drawing.occupied(cell.getX(), cell.getY(), cell.getZ(), looking)) {
                return false;
            }
        }
        return true;
    }

    @StarlarkMethod(
        name = "overlaps",
        doc = "Whether any cell of a scope is taken by what by names.",
        parameters = {@Param(name = "scope", named = true), @Param(name = "by", named = true)},
        useStarlarkThread = true)
    public boolean overlaps(Scope scope, Object by, StarlarkThread thread) throws EvalException {
        return overlapping(Derivation.of(thread), scope, Derivation.By.parse(by));
    }

    private static boolean overlapping(Derivation drawing, Scope scope, Derivation.By looking)
            throws EvalException {
        BoundingBox box = scope.bounds();
        for (BlockPos cell : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                box.maxZ())) {
            if (scope.holds(cell.getX() + 0.5D, cell.getY() + 0.5D, cell.getZ() + 0.5D)
                && drawing.occupied(cell.getX(), cell.getY(), cell.getZ(), looking)) {
                return true;
            }
        }
        return false;
    }

    @StarlarkMethod(
        name = "touches",
        doc = "Whether a scope, itself clear of what by names, has it right beside it. A face looks only outward.",
        parameters = {@Param(name = "scope", named = true), @Param(name = "by", named = true)},
        useStarlarkThread = true)
    public boolean touches(Scope scope, Object by, StarlarkThread thread) throws EvalException {
        Derivation drawing = Derivation.of(thread);
        Derivation.By looking = Derivation.By.parse(by);
        if (overlapping(drawing, scope, looking)) {
            return false;
        }
        Vec3 out = scope.outward();
        List<Direction> ways = out == null ? List.of(Direction.values()) : List.of(Frame.nearest(out));
        BoundingBox box = scope.bounds();
        for (BlockPos cell : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                box.maxZ())) {
            if (!scope.holds(cell.getX() + 0.5D, cell.getY() + 0.5D, cell.getZ() + 0.5D)) {
                continue;
            }
            for (Direction way : ways) {
                BlockPos beside = cell.relative(way);
                if (!scope.holds(beside.getX() + 0.5D, beside.getY() + 0.5D, beside.getZ() + 0.5D)
                    && drawing.occupied(beside.getX(), beside.getY(), beside.getZ(), looking)) {
                    return true;
                }
            }
        }
        return false;
    }

    @StarlarkMethod(
        name = "distance",
        doc = "How many cells out from a scope the nearest of what to names lies (0 when it overlaps), or None"
            + " if nothing lies within.",
        parameters = {
            @Param(name = "scope", named = true), @Param(name = "to", named = true),
            @Param(name = "within", defaultValue = "16", named = true, positional = false)},
        useStarlarkThread = true,
        allowReturnNones = true)
    public StarlarkInt distance(Scope scope, Object to, StarlarkInt within, StarlarkThread thread)
            throws EvalException {
        Derivation drawing = Derivation.of(thread);
        Derivation.By looking = Derivation.By.parse(to);
        BoundingBox box = scope.bounds();
        int reach = Math.min(within.toInt("within"), MOST_WITHIN);
        if (overlapping(drawing, scope, looking)) {
            return StarlarkInt.of(0);
        }
        for (int ring = 1; ring <= reach; ring++) {
            BoundingBox outer = box.inflatedBy(ring);
            for (BlockPos cell : BlockPos.betweenClosed(outer.minX(), outer.minY(), outer.minZ(), outer.maxX(),
                    outer.maxY(), outer.maxZ())) {
                boolean onRing = cell.getX() == outer.minX() || cell.getX() == outer.maxX()
                    || cell.getY() == outer.minY() || cell.getY() == outer.maxY()
                    || cell.getZ() == outer.minZ() || cell.getZ() == outer.maxZ();
                if (onRing && drawing.occupied(cell.getX(), cell.getY(), cell.getZ(), looking)) {
                    return StarlarkInt.of(ring);
                }
            }
        }
        return null;
    }

    @StarlarkMethod(
        name = "tally",
        doc = "How many parts and rule calls earlier phases made under a label. As with occluded, never what"
            + " this rule or those above it drew.",
        parameters = {@Param(name = "label", named = true)},
        useStarlarkThread = true)
    public StarlarkInt tally(String label, StarlarkThread thread) throws EvalException {
        return StarlarkInt.of(Derivation.of(thread).tally(label));
    }

    @StarlarkMethod(
        name = "extend",
        doc = "A region grown from its edge in one direction (down, up, north, south, east or west) until it"
            + " meets a cell of a kind, at most max cells. Running out of max before meeting one stops the"
            + " drawing. keep = False answers only what was grown, or None if nothing was.",
        parameters = {
            @Param(name = "region"),
            @Param(name = "direction", defaultValue = "'down'", named = true),
            @Param(name = "until", defaultValue = "'ground'", named = true, positional = false),
            @Param(name = "max", defaultValue = "32", named = true, positional = false),
            @Param(name = "keep", defaultValue = "True", named = true, positional = false)},
        useStarlarkThread = true,
        allowReturnNones = true)
    public Shape extend(Object region, String direction, String until, StarlarkInt max, boolean keep,
            StarlarkThread thread) throws EvalException {
        Derivation drawing = Derivation.of(thread);
        Direction step = Direction.byName(direction.toLowerCase(Locale.ROOT));
        if (step == null) {
            throw Starlark.errorf("a region extends up, down, north, south, east or west, not '%s'", direction);
        }
        Solid solid = solidOf(region);
        BlockClass wanted = BlockClass.parse(until);
        int limit = max.toInt("max");
        Set<BlockPos> inside = cellsOf(solid);
        Set<BlockPos> grown = new HashSet<>();
        for (BlockPos cell : inside) {
            if (inside.contains(cell.relative(step))) {
                continue;
            }
            List<BlockPos> run = new ArrayList<>();
            boolean met = false;
            for (int cells = 1; cells <= limit; cells++) {
                BlockPos at = cell.relative(step, cells);
                if (inside.contains(at) || drawing.belowWorld(at.getY())
                    || wanted.matches(drawing.read(at.getX(), at.getY(), at.getZ()))) {
                    met = true;
                    break;
                }
                run.add(at);
            }
            if (!met) {
                throw drawing.stop(DraftRefusal.NO_GROUND, "nothing " + until + " within " + limit + " cells "
                    + step.getName() + " of " + cell.toShortString());
            }
            grown.addAll(run);
        }
        if (grown.isEmpty()) {
            return keep ? new Shape(solid) : null;
        }
        Solid added = new Solid.Cells(grown);
        return new Shape(keep ? new Solid.Group(List.of(solid, added)) : added);
    }

    @StarlarkMethod(
        name = "fill_below",
        doc = "Only what lies between a region's underside and the ground below it, or None where it already"
            + " stands on the ground.",
        parameters = {
            @Param(name = "region"),
            @Param(name = "until", defaultValue = "'ground'", named = true, positional = false),
            @Param(name = "max", defaultValue = "32", named = true, positional = false)},
        useStarlarkThread = true,
        allowReturnNones = true)
    public Shape fillBelow(Object region, String until, StarlarkInt max, StarlarkThread thread) throws EvalException {
        return extend(region, "down", until, max, false, thread);
    }

    @StarlarkMethod(
        name = "terrain",
        doc = "The cells of a scope that are of a kind ('solid' by default), as a shape, or None if there are none."
            + " clear('dig', terrain(...)) digs out only what is there.",
        parameters = {
            @Param(name = "scope"),
            @Param(name = "of", defaultValue = "'solid'", named = true, positional = false)},
        useStarlarkThread = true,
        allowReturnNones = true)
    public Shape terrain(Scope scope, String of, StarlarkThread thread) throws EvalException {
        Derivation drawing = Derivation.of(thread);
        BlockClass wanted = BlockClass.parse(of);
        BoundingBox box = scope.bounds();
        Set<BlockPos> found = new HashSet<>();
        for (BlockPos cell : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                box.maxZ())) {
            if (scope.holds(cell.getX() + 0.5D, cell.getY() + 0.5D, cell.getZ() + 0.5D)
                && wanted.matches(drawing.read(cell.getX(), cell.getY(), cell.getZ()))) {
                found.add(cell.immutable());
            }
        }
        return found.isEmpty() ? null : new Shape(new Solid.Cells(found));
    }

    @StarlarkMethod(
        name = "conform",
        doc = "A region lifted column by column so its underside lies offset cells above the one cell over the"
            + " ground. smooth reads the ground between columns, so a slope comes out as slabs and stairs when"
            + " the part is fitted 'stepped'.",
        parameters = {
            @Param(name = "region"),
            @Param(name = "offset", defaultValue = "0", named = true, positional = false),
            @Param(name = "smooth", defaultValue = "False", named = true, positional = false)},
        useStarlarkThread = true)
    public Shape conform(Object region, StarlarkInt offset, boolean smooth, StarlarkThread thread)
            throws EvalException {
        Derivation drawing = Derivation.of(thread);
        Solid solid = solidOf(region);
        BoundingBox box = solid.bounds();
        int width = Scope.span(box, Direction.Axis.X);
        int depth = Scope.span(box, Direction.Axis.Z);
        double[] lift = new double[width * depth];
        int above = offset.toInt("offset");
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                Integer ground = drawing.ground(box.minX() + x, box.minZ() + z);
                if (ground == null) {
                    throw drawing.stop(DraftRefusal.NO_GROUND, "no ground under column (" + (box.minX() + x) + ", "
                        + (box.minZ() + z) + ")");
                }
                lift[z * width + x] = ground + 1 + above - box.minY();
            }
        }
        return new Shape(new Solid.Lifted(solid, box.minX(), box.minZ(), width, lift, smooth));
    }

    private static Set<BlockPos> cellsOf(Solid solid) {
        BoundingBox box = solid.bounds();
        Set<BlockPos> cells = new HashSet<>();
        for (BlockPos cell : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                box.maxZ())) {
            if (Octants.of(solid, cell.getX(), cell.getY(), cell.getZ()) != Octants.NONE) {
                cells.add(cell.immutable());
            }
        }
        return cells;
    }

    private static Direction.Axis longest(BoundingBox box) {
        int x = Scope.span(box, Direction.Axis.X);
        int y = Scope.span(box, Direction.Axis.Y);
        int z = Scope.span(box, Direction.Axis.Z);
        if (y >= x && y >= z) {
            return Direction.Axis.Y;
        }
        return x >= z ? Direction.Axis.X : Direction.Axis.Z;
    }

    static Solid solidOf(Object region) throws EvalException {
        if (region instanceof Shape shape) {
            return shape.solid();
        }
        if (region instanceof Scope scope) {
            return scope.solid();
        }
        if (region instanceof Made made) {
            return made.part().solid();
        }
        if (region instanceof Sequence<?> many) {
            List<Solid> solids = new ArrayList<>();
            for (Object each : many) {
                solids.add(solidOf(each));
            }
            return shaped(() -> new Solid.Group(solids));
        }
        throw Starlark.errorf("this takes a shape, a scope, a part or a list of them, got %s",
            Starlark.type(region));
    }

    private static Sequence<?> sequenceOf(Object written, String wanted) throws EvalException {
        if (written instanceof Sequence<?> many) {
            return many;
        }
        throw Starlark.errorf("%s, got %s", wanted, Starlark.type(written));
    }

    private static Materials materialsOf(Object palette) throws EvalException {
        if (palette instanceof Materials materials) {
            return materials;
        }
        if (palette instanceof Sequence<?> listed) {
            List<ItemFilter> admits = filters(listed);
            return shaped(() -> Materials.inOrder(admits));
        }
        throw Starlark.errorf("a palette is a list of blocks or mix(...), got %s", Starlark.type(palette));
    }

    private static Set<String> labelsOf(Object labels) throws EvalException {
        return new LinkedHashSet<>(Rule.strings(labels));
    }

    static Map<String, Object> attrsOf(Dict<?, ?> written) throws EvalException {
        Map<String, Object> handed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : written.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw Starlark.errorf("an attribute is named with a string, got %s", Starlark.type(entry.getKey()));
            }
            handed.put(name, entry.getValue());
        }
        return handed;
    }

    private static Massing.Over overOf(Object over) throws EvalException {
        if (over instanceof String word) {
            return switch (word.toLowerCase(Locale.ROOT)) {
                case "all" -> Massing.Over.ALL;
                case "empty" -> Massing.Over.EMPTY;
                default -> new Massing.Over(Massing.Over.Kind.LABELS, Set.of(word));
            };
        }
        return new Massing.Over(Massing.Over.Kind.LABELS, Set.copyOf(Rule.strings(over)));
    }

    private static BlockState stateOf(String written, StarlarkThread thread) throws EvalException {
        int bracket = written.indexOf('[');
        ResourceLocation block = ResourceLocation.tryParse(bracket < 0 ? written : written.substring(0, bracket));
        if (block != null && !BuiltInRegistries.BLOCK.containsKey(block)) {
            throw Derivation.of(thread).stop(DraftRefusal.UNKNOWN_BLOCK, block.toString());
        }
        return stateOf(written);
    }

    private static BlockState stateOf(String written) throws EvalException {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), written, false).blockState();
        } catch (CommandSyntaxException unreadable) {
            throw Starlark.errorf("'%s' is not a block state: %s", written, unreadable.getMessage());
        }
    }

    private interface Making<T> {
        T make();
    }

    private static <T> T shaped(Making<T> making) throws EvalException {
        try {
            return making.make();
        } catch (IllegalArgumentException refused) {
            throw Starlark.errorf("%s", refused.getMessage());
        }
    }

    static List<ItemFilter> filters(Sequence<?> written) throws EvalException {
        List<ItemFilter> filters = new ArrayList<>();
        for (Object entry : written) {
            filters.add(filter(String.valueOf(entry)));
        }
        return filters;
    }

    private static ItemFilter filter(String written) throws EvalException {
        return written.startsWith("#") ? ItemFilter.tag(id(written.substring(1))) : ItemFilter.item(id(written));
    }

    private static ResourceLocation id(String written) throws EvalException {
        ResourceLocation parsed = ResourceLocation.tryParse(written);
        if (parsed == null) {
            throw Starlark.errorf("'%s' is not an id", written);
        }
        return parsed;
    }

    private static ResourceLocation option(String written) throws EvalException {
        if (written.contains(":")) {
            return id(written);
        }
        ResourceLocation plain = ResourceLocation.tryBuild(DraftSite.PLAIN, written);
        if (plain == null) {
            throw Starlark.errorf("a choice option is a word of a-z, 0-9, _, - and ., or an id; got '%s'", written);
        }
        return plain;
    }
}
