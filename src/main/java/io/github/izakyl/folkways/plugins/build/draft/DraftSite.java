package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkValue;

@StarlarkBuiltin(name = "site", doc = "What the player marked, what they filled in, and the ground it lies on.")
public final class DraftSite implements StarlarkValue {

    static final String PLAIN = "pattern";

    private final Derivation drawing;
    private final Commission commission;

    DraftSite(Derivation drawing) {
        this.drawing = drawing;
        this.commission = drawing.commission();
    }

    @StarlarkMethod(name = "kind", doc = "What the player marked: 'zone' or 'path'.", structField = true)
    public String kind() {
        return commission.hint().kind();
    }

    @StarlarkMethod(name = "zone", doc = "The marked box, facing the way the player asked. For a path, the box"
        + " around its points. It is a suggestion: a drawing may reach past it.", structField = true)
    public Scope zone() {
        return Scope.over(commission.hint().bounds(), commission.facing());
    }

    @StarlarkMethod(name = "path", doc = "The marked line of points, or None when a zone was marked.",
        structField = true, allowReturnNones = true)
    public PathLine path() {
        return commission.hint() instanceof Hint.Path path ? new PathLine(path.relative()) : null;
    }

    @StarlarkMethod(name = "facing", doc = "Which way the drawing's front faces: north, east, south or west.",
        structField = true)
    public String facing() {
        return commission.facing().getName();
    }

    @StarlarkMethod(name = "altitude", doc = "How high in the world the site's anchor lies, so a drawing can"
        + " reach a height the world itself names, such as a layer of ore.", structField = true)
    public StarlarkInt altitude() {
        return StarlarkInt.of(commission.hint().anchor().getY());
    }

    @StarlarkMethod(name = "round", doc = "Which round of a growing pattern this is, from 0. Always 0 for a"
        + " pattern that is drawn once.", structField = true)
    public StarlarkInt round() {
        return StarlarkInt.of(commission.growth().round());
    }

    @StarlarkMethod(name = "kept", doc = "What the last round of a growing pattern asked to keep, as a dict;"
        + " empty in round 0.", structField = true)
    public Dict<String, Object> kept() {
        return Kept.read(commission.growth().kept());
    }

    @StarlarkMethod(name = "flag", doc = "What the player set this switch to.", parameters = {@Param(name = "key")})
    public boolean flag(String key) {
        return commission.settings().flag(key);
    }

    @StarlarkMethod(name = "count", doc = "What the player dialled this number to.",
        parameters = {@Param(name = "key")})
    public StarlarkInt count(String key) {
        return StarlarkInt.of(commission.settings().count(key));
    }

    @StarlarkMethod(name = "choice", doc = "Which option the player picked, as the pattern wrote it.",
        parameters = {@Param(name = "key")})
    public String choice(String key) throws EvalException {
        ResourceLocation picked;
        try {
            picked = commission.settings().choice(key);
        } catch (RuntimeException undeclared) {
            throw Starlark.errorf("this pattern declares no choice called '%s'", key);
        }
        return PLAIN.equals(picked.getNamespace()) ? picked.getPath() : picked.toString();
    }

    @StarlarkMethod(name = "items", doc = "What the player put in this slot.", parameters = {@Param(name = "key")})
    public StarlarkList<String> items(String key) {
        List<String> written = new ArrayList<>();
        commission.settings().items(key).ifPresent(spec -> flatten(spec, written));
        return StarlarkList.immutableCopyOf(written);
    }

    private static void flatten(ItemSpec spec, List<String> into) {
        spec.item().ifPresent(id -> into.add(id.toString()));
        spec.tag().ifPresent(tag -> into.add("#" + tag.location()));
        for (ItemSpec alternative : spec.anyOf()) {
            flatten(alternative, into);
        }
    }

    @StarlarkMethod(name = "rand", doc = "A number below the bound, from this site's own seed.",
        parameters = {@Param(name = "bound")})
    public StarlarkInt rand(StarlarkInt bound) throws EvalException {
        int limit = bound.toInt("bound");
        if (limit < 1) {
            throw Starlark.errorf("rand needs a bound above zero, got %d", limit);
        }
        return StarlarkInt.of(drawing.variation().nextInt(limit));
    }

    @StarlarkMethod(name = "pick", doc = "One of a dict's keys, chosen by the whole-number weight it maps to.",
        parameters = {@Param(name = "weights")})
    public Object pick(Dict<?, ?> weights) throws EvalException {
        long total = 0;
        for (Object weight : weights.values()) {
            if (!(weight instanceof StarlarkInt whole) || whole.toInt("weight") < 0) {
                throw Starlark.errorf("pick weighs its keys by whole numbers of at least 0, got %s",
                    Starlark.repr(weight));
            }
            total += whole.toInt("weight");
        }
        if (total <= 0) {
            throw Starlark.errorf("pick needs at least one key weighing more than 0");
        }
        long roll = (long) (drawing.variation().nextDouble() * total);
        for (Map.Entry<?, ?> entry : weights.entrySet()) {
            roll -= ((StarlarkInt) entry.getValue()).toInt("weight");
            if (roll < 0) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("a weighted pick fell off its end");
    }

    @StarlarkMethod(name = "ground", doc = "The height of the ground in this column: its highest cell that is"
        + " solid and not a tree, looking through water. None where there is none within reach.",
        parameters = {@Param(name = "x"), @Param(name = "z")}, allowReturnNones = true)
    public StarlarkInt ground(StarlarkInt x, StarlarkInt z) throws EvalException {
        Integer found = drawing.ground(x.toInt("x"), z.toInt("z"));
        return found == null ? null : StarlarkInt.of(found);
    }

    @StarlarkMethod(name = "highest", doc = "The height of the highest cell in this column of a kind: 'fluid',"
        + " 'solid', 'foliage', a #tag, a block id, or several joined with '|'. None where there is none.",
        parameters = {@Param(name = "x"), @Param(name = "z"), @Param(name = "of")}, allowReturnNones = true)
    public StarlarkInt highest(StarlarkInt x, StarlarkInt z, String of) throws EvalException {
        Integer found = drawing.highest(x.toInt("x"), z.toInt("z"), BlockClass.parse(of));
        return found == null ? null : StarlarkInt.of(found);
    }

    @StarlarkMethod(name = "matches", doc = "Whether the cell at a point is of a kind.",
        parameters = {@Param(name = "at"), @Param(name = "of")})
    public boolean matches(Point at, String of) throws EvalException {
        return BlockClass.parse(of).matches(drawing.read(at.x(), at.y(), at.z()));
    }

    @StarlarkMethod(
        name = "probe",
        doc = "How many cells from a point, stepping up, down, north, south, east or west, until a cell of a kind."
            + " None if there is none within max.",
        parameters = {
            @Param(name = "origin"), @Param(name = "direction"),
            @Param(name = "until", defaultValue = "'ground'", named = true, positional = false),
            @Param(name = "max", defaultValue = "32", named = true, positional = false)},
        allowReturnNones = true)
    public StarlarkInt probe(Point origin, String direction, String until, StarlarkInt max) throws EvalException {
        Direction step = Direction.byName(direction.toLowerCase(Locale.ROOT));
        if (step == null) {
            throw Starlark.errorf("a probe steps up, down, north, south, east or west, not '%s'", direction);
        }
        BlockClass wanted = BlockClass.parse(until);
        int limit = max.toInt("max");
        for (int cells = 1; cells <= limit; cells++) {
            BlockPos at = origin.pos().relative(step, cells);
            if (wanted.matches(drawing.read(at.getX(), at.getY(), at.getZ()))) {
                return StarlarkInt.of(cells);
            }
        }
        return null;
    }

    @StarlarkMethod(
        name = "survey",
        doc = "What lies under a scope: the lowest, highest and median ground height over its columns, what"
            + " share of its columns are under water, and the blocks its ground is most made of.",
        parameters = {@Param(name = "scope")})
    public Survey survey(Scope scope) throws EvalException {
        BoundingBox box = scope.bounds();
        List<Integer> heights = new ArrayList<>();
        int wet = 0;
        int columns = 0;
        Map<String, Integer> surface = new LinkedHashMap<>();
        BlockClass wetOrGround = BlockClass.parse("fluid|ground");
        BlockClass fluid = BlockClass.parse("fluid");
        for (int z = box.minZ(); z <= box.maxZ(); z++) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                columns++;
                Integer top = drawing.highest(x, z, wetOrGround);
                if (top != null && fluid.matches(drawing.read(x, top, z))) {
                    wet++;
                }
                Integer ground = drawing.ground(x, z);
                if (ground != null) {
                    heights.add(ground);
                    BlockState state = drawing.read(x, ground, z);
                    surface.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), 1, Integer::sum);
                }
            }
        }
        heights.sort(Integer::compare);
        List<String> blocks = surface.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
            .map(Map.Entry::getKey).toList();
        return new Survey(heights.isEmpty() ? null : heights.get(0),
            heights.isEmpty() ? null : heights.get(heights.size() - 1),
            heights.isEmpty() ? null : heights.get((heights.size() - 1) / 2),
            columns == 0 ? 0.0D : (double) wet / columns, blocks);
    }

    @StarlarkMethod(
        name = "settle",
        doc = "The scope moved up or down to stand on the ground: its bottom one cell above the 'min', 'max',"
            + " 'median' or 'center' ground height of its columns, plus offset.",
        parameters = {
            @Param(name = "scope"),
            @Param(name = "at", defaultValue = "'min'", named = true, positional = false),
            @Param(name = "offset", defaultValue = "0", named = true, positional = false)})
    public Scope settle(Scope scope, String at, StarlarkInt offset) throws EvalException {
        int ground;
        String how = at.toLowerCase(Locale.ROOT);
        if (how.equals("center")) {
            Point middle = scope.center("low");
            Integer found = drawing.ground(middle.x(), middle.z());
            if (found == null) {
                throw drawing.stop(DraftRefusal.NO_GROUND, "no ground under the middle of " + Starlark.repr(scope));
            }
            ground = found;
        } else {
            Survey under = survey(scope);
            Integer found = switch (how) {
                case "min" -> under.low;
                case "max" -> under.high;
                case "median" -> under.middle;
                default -> throw Starlark.errorf("settle at 'min', 'max', 'median' or 'center', not '%s'", at);
            };
            if (found == null) {
                throw drawing.stop(DraftRefusal.NO_GROUND, "no ground under " + Starlark.repr(scope));
            }
            ground = found;
        }
        return scope.framed(scope.frame().raised(ground + 1 + offset.toInt("offset") - scope.bounds().minY()),
            scope.size());
    }

    @StarlarkBuiltin(name = "survey", doc = "What lies under a scope.")
    public record Survey(Integer low, Integer high, Integer middle, double wet, List<String> blocks)
        implements StarlarkValue {

        @StarlarkMethod(name = "min", doc = "The lowest ground height, or None.", structField = true,
            allowReturnNones = true)
        public StarlarkInt min() {
            return low == null ? null : StarlarkInt.of(low);
        }

        @StarlarkMethod(name = "max", doc = "The highest ground height, or None.", structField = true,
            allowReturnNones = true)
        public StarlarkInt max() {
            return high == null ? null : StarlarkInt.of(high);
        }

        @StarlarkMethod(name = "median", doc = "The median ground height, or None.", structField = true,
            allowReturnNones = true)
        public StarlarkInt median() {
            return middle == null ? null : StarlarkInt.of(middle);
        }

        @StarlarkMethod(name = "water", doc = "What share of the columns, 0 to 1, are under water.",
            structField = true)
        public StarlarkFloat water() {
            return StarlarkFloat.of(wet);
        }

        @StarlarkMethod(name = "blocks", doc = "The blocks the ground is made of, commonest first: a palette.",
            structField = true)
        public StarlarkList<String> blockList() {
            return StarlarkList.immutableCopyOf(blocks);
        }
    }

    @StarlarkBuiltin(name = "path", doc = "A marked line of points.")
    public record PathLine(List<BlockPos> cells) implements StarlarkValue {

        @StarlarkMethod(name = "points", doc = "The points, in the order they were marked.", structField = true)
        public StarlarkList<Point> points() {
            return StarlarkList.immutableCopyOf(cells.stream().map(Point::of).toList());
        }

        @StarlarkMethod(name = "length", doc = "How far the line runs across the ground, in cells.",
            structField = true)
        public StarlarkFloat length() {
            double total = 0.0D;
            for (int at = 1; at < cells.size(); at++) {
                total += across(cells.get(at - 1), cells.get(at));
            }
            return StarlarkFloat.of(total);
        }

        @StarlarkMethod(
            name = "every",
            doc = "One-cell scopes spaced this many cells apart along the line, starting offset cells in, each"
                + " facing the way the line runs there.",
            parameters = {
                @Param(name = "step"),
                @Param(name = "offset", defaultValue = "0", named = true, positional = false)})
        public StarlarkList<Scope> every(StarlarkInt step, StarlarkInt offset) throws EvalException {
            int spacing = step.toInt("step");
            if (spacing < 1) {
                throw Starlark.errorf("every steps at least one cell, got %d", spacing);
            }
            double next = offset.toInt("offset");
            double walked = 0.0D;
            List<Scope> found = new ArrayList<>();
            BlockPos last = null;
            for (int at = 1; at < cells.size(); at++) {
                BlockPos a = cells.get(at - 1);
                BlockPos b = cells.get(at);
                double length = across(a, b);
                boolean closing = at == cells.size() - 1;
                while (length > 0.0D && next <= walked + length + (closing ? 1e-9 : -1e-9)) {
                    double t = (next - walked) / length;
                    BlockPos cell = new BlockPos((int) Math.round(a.getX() + (b.getX() - a.getX()) * t),
                        (int) Math.round(a.getY() + (b.getY() - a.getY()) * t),
                        (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t));
                    if (!cell.equals(last)) {
                        found.add(Scope.over(new BoundingBox(cell), heading(a, b)));
                        last = cell;
                    }
                    next += spacing;
                }
                walked += length;
            }
            return StarlarkList.immutableCopyOf(found);
        }

        @StarlarkMethod(name = "headings", doc = "Which way each stretch of the line runs, one per stretch.",
            structField = true)
        public StarlarkList<String> headings() {
            List<String> found = new ArrayList<>();
            for (int at = 1; at < cells.size(); at++) {
                found.add(heading(cells.get(at - 1), cells.get(at)).getName());
            }
            return StarlarkList.immutableCopyOf(found);
        }

        @StarlarkMethod(
            name = "stretches",
            doc = "One scope per stretch of the line, a cell wide and a cell tall, standing on the cell the"
                + " stretch starts at and as deep as the stretch is long. Its own z lies along the stretch at"
                + " whatever angle that stretch runs, so what is drawn in it runs cornerwise when the line does.",
            structField = true)
        public StarlarkList<Scope> stretches() {
            List<Scope> found = new ArrayList<>();
            for (int at = 1; at < cells.size(); at++) {
                found.add(lying(cells.get(at - 1), cells.get(at)));
            }
            return StarlarkList.immutableCopyOf(found);
        }

        private static Scope lying(BlockPos from, BlockPos to) {
            double run = across(from, to);
            Vec3 forward = run == 0.0D ? Frame.way(heading(from, to))
                : new Vec3(to.getX() - from.getX(), 0.0D, to.getZ() - from.getZ()).scale(1.0D / run);
            Vec3 up = Frame.way(Direction.UP);
            Vec3 right = forward.cross(up);
            Vec3 corner = new Vec3(from.getX() + 0.5D - (right.x + forward.x) / 2.0D, from.getY(),
                from.getZ() + 0.5D - (right.z + forward.z) / 2.0D);
            return Scope.of(new Frame(corner, right, up, forward), 1, 1, (int) Math.round(run) + 1, "", "", false);
        }

        private static double across(BlockPos a, BlockPos b) {
            return Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        }

        private static Direction heading(BlockPos a, BlockPos b) {
            int dx = b.getX() - a.getX();
            int dz = b.getZ() - a.getZ();
            if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
                return dx > 0 ? Direction.EAST : Direction.WEST;
            }
            return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }

        List<double[]> centres() {
            return cells.stream().map(cell -> new double[] {cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D})
                .toList();
        }

        @Override
        public boolean isImmutable() {
            return true;
        }

        @Override
        public String toString() {
            return Arrays.toString(cells.toArray());
        }
    }
}
