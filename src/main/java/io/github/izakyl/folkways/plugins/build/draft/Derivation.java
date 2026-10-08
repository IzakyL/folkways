package io.github.izakyl.folkways.plugins.build.draft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkThread;

final class Derivation {

    static final int READ_BUDGET = 262_144;
    static final int REACH = 96;
    private static final BlockClass SOLID = unchecked("solid");
    private static final BlockClass GROUND = unchecked("ground");

    private static final Comparator<Queued> ORDER =
        Comparator.comparingInt((Queued waiting) -> -waiting.node().priority()).thenComparingInt(Queued::order);

    private final Commission commission;
    private final List<String> phases;
    private final List<PriorityQueue<Queued>> queues = new ArrayList<>();
    private final List<Committed> parts = new ArrayList<>();
    private final List<Joint> joints = new ArrayList<>();
    private final List<List<Marked>> marked = new ArrayList<>();
    private final List<Plane> planes = new ArrayList<>();
    private final Map<String, Integer> siblings = new HashMap<>();
    private final Map<Long, Integer> occupancy = new HashMap<>();
    private final Map<String, Random> rolls = new HashMap<>();
    private final Map<String, Double> reports = new LinkedHashMap<>();
    private int painted;
    private int queued;

    private int phase;
    private Rule.Node current;
    private int reads;
    private DraftRefusal stoppedFor;
    private Vocabulary.Grown grown;

    Derivation(Commission commission, List<String> phases) {
        this.commission = commission;
        this.phases = List.copyOf(phases);
        for (int index = 0; index < this.phases.size(); index++) {
            queues.add(new PriorityQueue<>(ORDER));
            marked.add(new ArrayList<>());
        }
    }

    record Committed(Massing.Part part, Rule.Node owner, int phase) {}

    record Marked(Scope scope, Set<String> labels, Rule.Node node) {}

    record Plane(String label, Direction.Axis axis, int boundary, int phase) {}

    private record Queued(Rule.Node node, int order) {}

    record By(boolean terrain, boolean anyPart, Set<String> labels) {

        static By parse(Object written) throws EvalException {
            boolean terrain = false;
            boolean anyPart = false;
            Set<String> labels = new LinkedHashSet<>();
            for (String token : Rule.strings(written)) {
                switch (token) {
                    case "terrain" -> terrain = true;
                    case "parts" -> anyPart = true;
                    case "world" -> {
                        terrain = true;
                        anyPart = true;
                    }
                    default -> labels.add(token);
                }
            }
            return new By(terrain, anyPart, labels);
        }
    }

    static Derivation of(StarlarkThread thread) throws EvalException {
        Derivation drawing = thread.getThreadLocal(Derivation.class);
        if (drawing == null) {
            throw Starlark.errorf("this only works while a pattern draws, inside draw(site) or a rule");
        }
        return drawing;
    }

    Commission commission() {
        return commission;
    }

    Random variation() {
        return rolls.computeIfAbsent(where(),
            at -> new Random(scramble(commission.seed()) ^ scramble(at.hashCode())));
    }

    private static long scramble(long value) {
        long mixed = value * 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ (mixed >>> 32)) * 0xD6E8FEB86659FD93L;
        return mixed ^ (mixed >>> 32);
    }

    List<Joint> joints() {
        return List.copyOf(joints);
    }

    DraftRefusal stoppedFor() {
        return stoppedFor;
    }

    Map<String, Double> reports() {
        return Map.copyOf(reports);
    }

    void report(String key, Object value) throws EvalException {
        if (value instanceof StarlarkInt whole) {
            reports.merge(key, (double) whole.toInt("value"), Double::sum);
        } else if (value instanceof StarlarkFloat part) {
            reports.merge(key, part.toDouble(), Double::sum);
        } else {
            reports.merge(key + "=" + Starlark.str(value), 1.0D, Double::sum);
        }
    }

    Vocabulary.Grown grown() {
        return grown;
    }

    Object attribute(String name) {
        return current == null ? null : current.attribute(name);
    }

    List<Massing.Part> derive(StarlarkThread thread, Object draw, DraftSite site)
            throws EvalException, InterruptedException {
        thread.setThreadLocal(Derivation.class, this);
        phase = 0;
        Object answered = Starlark.call(thread, draw, List.of(site), Map.of());
        if (answered instanceof Vocabulary.Grown round) {
            grown = round;
            answered = round.parts();
        }
        collect(null, answered);
        for (phase = 0; phase < phases.size(); phase++) {
            PriorityQueue<Queued> queue = queues.get(phase);
            while (!queue.isEmpty()) {
                Rule.Node node = queue.poll().node();
                current = node;
                List<Object> positional = new ArrayList<>(node.args().size() + 2);
                positional.add(site);
                positional.add(node.scope());
                positional.addAll(node.args());
                Object made;
                try {
                    made = Starlark.call(thread, node.rule().fn(), positional, node.kwargs());
                } catch (EvalException failed) {
                    throw stoppedFor != null ? failed : Starlark.errorf("%s: %s", node.path(), failed.getMessage());
                }
                collect(node, made);
            }
            current = null;
        }
        List<Massing.Part> drawn = new ArrayList<>(parts.size());
        for (Committed committed : parts) {
            drawn.add(committed.part());
        }
        return drawn;
    }

    int phaseOf(Rule rule) throws EvalException {
        if (rule.phase() == null) {
            return phase;
        }
        int at = phases.indexOf(rule.phase());
        if (at < 0) {
            throw Starlark.errorf("rule '%s' runs in phase '%s', and this pattern's phases are %s", rule.name(),
                rule.phase(), phases);
        }
        if (at < phase) {
            throw Starlark.errorf("rule '%s' runs in phase '%s', which finished before phase '%s' called it",
                rule.name(), rule.phase(), phases.get(phase));
        }
        return at;
    }

    private void collect(Rule.Node owner, Object made) throws EvalException {
        if (made == null || made == Starlark.NONE) {
            return;
        }
        if (made instanceof Sequence<?> many) {
            for (Object each : many) {
                collect(owner, each);
            }
            return;
        }
        if (made instanceof Vocabulary.Joined joined) {
            joints.add(joined.joint());
            return;
        }
        if (made instanceof Vocabulary.Made one) {
            parts.add(new Committed(one.part().at(childPath(owner, one.part().role())), owner, phase));
            return;
        }
        if (made instanceof Vocabulary.Grown) {
            throw Starlark.errorf("grown(...) is what grow(site) itself answers, not something to draw inside it");
        }
        if (made instanceof Rule.Node node) {
            if (node.phase() < phase) {
                throw Starlark.errorf("rule '%s' belongs to phase '%s', which has already finished", node.rule().name(),
                    phases.get(node.phase()));
            }
            Rule.Node placed = node.placed(owner, childPath(owner, node.segment()));
            queues.get(placed.phase()).add(new Queued(placed, queued++));
            if (!placed.labels().isEmpty()) {
                marked.get(placed.phase()).add(new Marked(placed.scope(), placed.labels(), placed));
            }
            return;
        }
        throw Starlark.errorf("a pattern draws parts, joints, rule calls, or lists of them; got %s", Starlark.type(made));
    }

    private String childPath(Rule.Node owner, String base) {
        String prefix = owner == null ? "" : owner.path() + "/";
        int seen = siblings.merge(prefix + base, 1, Integer::sum) - 1;
        return prefix + base + (seen > 0 ? "[" + seen + "]" : "");
    }

    String where() {
        return current == null ? "draw" : current.path();
    }

    void emit(String label, Direction.Axis axis, int boundary) {
        planes.add(new Plane(label, axis, boundary, phase));
    }

    List<Integer> planes(String label, Direction.Axis axis) {
        List<Integer> found = new ArrayList<>();
        for (Plane plane : planes) {
            if (plane.phase() < phase && plane.axis() == axis && plane.label().equals(label)) {
                found.add(plane.boundary());
            }
        }
        return found;
    }

    boolean occupied(int x, int y, int z, By by) throws EvalException {
        if (by.anyPart() || !by.labels().isEmpty()) {
            paintFinished();
            Integer index = occupancy.get(BlockPos.asLong(x, y, z));
            if (index != null) {
                Committed hit = parts.get(index);
                if (!(hit.part().skin() instanceof Skin.Cleared) && !hidden(hit.owner())
                    && (by.anyPart() || hit.part().knownAny(by.labels()))) {
                    return true;
                }
            }
            if (!by.labels().isEmpty()) {
                for (int finished = 0; finished < phase; finished++) {
                    for (Marked mark : marked.get(finished)) {
                        if (!hidden(mark.node()) && mark.scope().holds(x + 0.5D, y + 0.5D, z + 0.5D)
                            && labelled(mark.labels(), by.labels())) {
                            return true;
                        }
                    }
                }
            }
        }
        return by.terrain() && SOLID.matches(read(x, y, z));
    }

    int tally(String label) {
        int count = 0;
        for (Committed committed : parts) {
            if (committed.phase() < phase && !hidden(committed.owner()) && committed.part().known(label)) {
                count++;
            }
        }
        for (int finished = 0; finished < phase; finished++) {
            for (Marked mark : marked.get(finished)) {
                if (!hidden(mark.node()) && mark.labels().contains(label)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean labelled(Set<String> have, Set<String> wanted) {
        for (String each : have) {
            if (wanted.contains(each)) {
                return true;
            }
        }
        return false;
    }

    private boolean hidden(Rule.Node owner) {
        return owner != null && current != null && current.within(owner);
    }

    private void paintFinished() {
        while (painted < parts.size() && parts.get(painted).phase() < phase) {
            Massing.Part part = parts.get(painted).part();
            BoundingBox box = part.solid().bounds();
            if (Lattice.volume(box) <= Lattice.MOST_SCANNED) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        for (int x = box.minX(); x <= box.maxX(); x++) {
                            if (Octants.of(part.solid(), x, y, z) == Octants.NONE) {
                                continue;
                            }
                            long key = BlockPos.asLong(x, y, z);
                            Integer before = occupancy.get(key);
                            if (before == null || part.over().takes(parts.get(before).part())) {
                                occupancy.put(key, painted);
                            }
                        }
                    }
                }
            }
            painted++;
        }
    }

    BlockState read(int x, int y, int z) throws EvalException {
        BoundingBox marked = commission.growth().reach();
        if (x < marked.minX() - REACH || x > marked.maxX() + REACH || z < marked.minZ() - REACH
            || z > marked.maxZ() + REACH || y < marked.minY() - REACH || y > marked.maxY() + REACH) {
            throw stop(DraftRefusal.READ_TOO_FAR, "(" + x + ", " + y + ", " + z + ")");
        }
        if (++reads > READ_BUDGET) {
            throw stop(DraftRefusal.READ_BUDGET, Integer.toString(READ_BUDGET));
        }
        BlockPos at = commission.hint().anchor().offset(x, y, z);
        return commission.world().block(at).orElseThrow(() -> stop(DraftRefusal.UNLOADED, at.toShortString()));
    }

    Integer highest(int x, int z, BlockClass of) throws EvalException {
        BlockPos anchor = commission.hint().anchor();
        BoundingBox marked = commission.growth().reach();
        OptionalInt top = commission.world().top(anchor.getX() + x, anchor.getZ() + z);
        if (top.isEmpty()) {
            throw stop(DraftRefusal.UNLOADED, new BlockPos(anchor.getX() + x, anchor.getY(),
                anchor.getZ() + z).toShortString());
        }
        long start = Math.min((long) top.getAsInt() - 1 - anchor.getY(), marked.maxY() + REACH);
        long end = Math.max((long) marked.minY() - REACH, (long) commission.world().floor() - anchor.getY());
        for (long y = start; y >= end; y--) {
            if (of.matches(read(x, (int) y, z))) {
                return (int) y;
            }
        }
        return null;
    }

    boolean belowWorld(int y) {
        return (long) commission.hint().anchor().getY() + y < commission.world().floor();
    }

    Integer ground(int x, int z) throws EvalException {
        return highest(x, z, GROUND);
    }

    EvalException stop(DraftRefusal why, String detail) {
        stoppedFor = why;
        return new EvalException(where() + ": " + detail);
    }

    private static BlockClass unchecked(String written) {
        try {
            return BlockClass.parse(written);
        } catch (EvalException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
