package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * The order a build goes up in, worked out once from the whole target: every piece of work outward from one plane,
 * up from it layer by layer above and down from it layer by layer below, after only what it stands on.
 *
 * <p>A piece waits for the piece next toward the plane under (or over) it, and for whatever holds it up: a block it
 * hangs from, the block under sand, the walls water would run out through, the things on a block cleared before it.
 * Nothing waits for room to stand in or a way to walk, so no piece is ever refused for want of them: those are for
 * the work to find when it is done.
 */
final class Layering {

    enum Kind {
        LAY,
        STRIP,
        DRAIN,
        POUR,
        JOIN,
        SCAFFOLD
    }

    record Work(int id, Kind kind, BlockPos focus, Optional<Step> step, Optional<Joint> joint, boolean up, int band,
            long key, List<BlockPos> stances) {
    }

    record Plan(int plane, List<Work> works, Map<Integer, Set<Integer>> after, Map<BlockPos, BuildRefusal> refused,
            int held, int led, int broken) {

        /**
         * How many pieces stand before each, on the longest line of waiting that leads to it. Every wait runs from a
         * lower key to a higher one, so the works in key order are already in an order they can be done in.
         */
        Map<Integer, Integer> waves() {
            Map<Integer, Integer> wave = new HashMap<>();
            for (Work work : works) {
                int deepest = 0;
                for (int before : after.getOrDefault(work.id(), Set.of())) {
                    deepest = Math.max(deepest, wave.getOrDefault(before, 0) + 1);
                }
                wave.put(work.id(), deepest);
            }
            return wave;
        }
    }

    private static final int REACH = 2;
    private static final int MOST_FLOODED = 1 << 21;
    private static final int MOST_DRAINED = 256;
    private static final long HOP = 1L;
    private static final int BAND_SHIFT = 20;
    private static final int STANCE_SPAN = 2;
    private static final int STANCE_DROP = 4;
    private static final int FAR_DROP = 24;
    private static final int MOST_STANCES = 12;
    private static final int SCAFFOLD_SPREAD = 2;
    private static final int SCAFFOLD_BELOW = 2;
    private static final int TALLEST_SCAFFOLD = 32;
    private static final int MOST_FROM_ONE_SCAFFOLD = 8;

    private final LevelReader level;
    private final BuildTarget target;
    private final List<Draft> drafts = new ArrayList<>();
    private final Map<BlockPos, Integer> owner = new HashMap<>();
    private final Map<BlockPos, BuildRefusal> refused = new LinkedHashMap<>();
    private final Map<Integer, Set<Integer>> hard = new HashMap<>();
    private final Set<BlockPos> latched;
    private final Map<BlockPos, Long> changedAt = new HashMap<>();
    private final Map<BlockPos, BlockState> changedTo = new HashMap<>();
    // Each scaffolded cell, and the keys between which its scaffold stands: over the first, up to the second.
    private final Map<BlockPos, long[]> scaffolded = new HashMap<>();

    private Layering(LevelReader level, BuildTarget target, Set<BlockPos> latched) {
        this.level = level;
        this.target = target;
        this.latched = latched;
    }

    private static final class Draft {
        final int id;
        final Kind kind;
        final BlockPos focus;
        @Nullable
        final Step step;
        @Nullable
        final Joint joint;
        boolean dropped;

        Draft(int id, Kind kind, BlockPos focus, @Nullable Step step, @Nullable Joint joint) {
            this.id = id;
            this.kind = kind;
            this.focus = focus;
            this.step = step;
            this.joint = joint;
        }

        Map<BlockPos, BlockState> after() {
            return step == null ? Map.of() : step.after();
        }
    }

    static Plan of(LevelReader level, BuildTarget target) {
        return of(level, target, Set.of());
    }

    /** Plans what is left of the target: every cell not met yet, and not one of {@code latched}, met once already. */
    static Plan of(LevelReader level, BuildTarget target, Set<BlockPos> latched) {
        return new Layering(level, target, latched).plan();
    }

    private Plan plan() {
        lay(pending());
        joints();
        Sketch done = new Sketch(level);
        drafts.stream().filter(draft -> !draft.dropped).forEach(draft -> done.drawAll(draft.after()));
        for (Draft draft : drafts) {
            switch (draft.kind) {
                case LAY, POUR -> held(draft, done);
                case STRIP -> cleared(draft);
                case DRAIN -> drained(draft);
                case JOIN, SCAFFOLD -> { }
            }
        }
        for (Draft draft : drafts) {
            if (!draft.dropped && wet(target.wanted(draft.focus))) {
                walledIn(draft);
            }
        }
        Opening opening = opening();
        int plane = opening.plane();
        Map<Integer, Long> keys = new HashMap<>();
        Map<Integer, Integer> parent = new HashMap<>();
        int broken = spread(opening, keys, parent);
        Map<Integer, Set<Integer>> after = new HashMap<>();
        int held = 0;
        for (Map.Entry<Integer, Set<Integer>> edges : hard.entrySet()) {
            if (keys.containsKey(edges.getKey())) {
                for (int before : edges.getValue()) {
                    if (keys.containsKey(before)) {
                        after.computeIfAbsent(edges.getKey(), id -> new LinkedHashSet<>()).add(before);
                        held++;
                    }
                }
            }
        }
        int led = 0;
        for (Map.Entry<Integer, Integer> lead : parent.entrySet()) {
            if (keys.get(lead.getValue()) < keys.get(lead.getKey())
                && after.computeIfAbsent(lead.getKey(), id -> new LinkedHashSet<>()).add(lead.getValue())) {
                led++;
            }
        }
        for (Draft draft : live()) {
            long key = keys.get(draft.id);
            draft.after().forEach((cell, state) -> {
                changedAt.merge(cell, key, Math::min);
                changedTo.put(cell, state);
            });
        }
        for (Scaffold scaffold : scaffold(keys)) {
            Draft raise = add(Kind.SCAFFOLD, scaffold.column.getFirst(), Step.raise(scaffold.column), null);
            Draft lower = add(Kind.SCAFFOLD, scaffold.column.getFirst(), Step.lower(scaffold.column), null);
            keys.put(raise.id, scaffold.raised);
            keys.put(lower.id, scaffold.lowered);
            for (Draft served : scaffold.served) {
                after.computeIfAbsent(served.id, id -> new LinkedHashSet<>()).add(raise.id);
                after.computeIfAbsent(lower.id, id -> new LinkedHashSet<>()).add(served.id);
            }
        }
        List<Work> works = new ArrayList<>();
        for (Draft draft : live()) {
            long key = keys.get(draft.id);
            works.add(new Work(draft.id, draft.kind, draft.focus, Optional.ofNullable(draft.step),
                Optional.ofNullable(draft.joint), draft.focus.getY() >= plane, band(draft, plane), key,
                stances(draft, key)));
        }
        works.sort(Comparator.comparingLong(Work::key).thenComparingInt(Work::id));
        return new Plan(plane, List.copyOf(works), after, Map.copyOf(refused), held, led, broken);
    }

    private List<Draft> live() {
        return drafts.stream().filter(draft -> !draft.dropped).toList();
    }

    private List<BlockPos> pending() {
        List<BlockPos> pending = new ArrayList<>();
        target.unloaded().forEach(cell -> refused.put(cell, BuildRefusal.UNKNOWN_BLOCK));
        for (Map.Entry<BlockPos, BlockState> cell : target.cells().entrySet()) {
            BlockPos pos = cell.getKey();
            if (!level.hasChunkAt(pos)) {
                refused.put(pos, BuildRefusal.CELL_NOT_LOADED);
            } else if (!latched.contains(pos) && !BuildPlanner.satisfied(level.getBlockState(pos), cell.getValue())) {
                pending.add(pos);
            }
        }
        pending.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ));
        return pending;
    }

    private void lay(List<BlockPos> pending) {
        Set<BlockPos> open = new HashSet<>(pending);
        for (BlockPos cell : pending) {
            if (owner.containsKey(cell) || refused.containsKey(cell)) {
                continue;
            }
            BlockState goal = target.wanted(cell);
            BlockState standing = level.getBlockState(cell);
            if (goal.isAir() && standing.liquid()) {
                drain(cell, standing.getFluidState().getType(), open);
            } else if (goal.isAir()) {
                if (unbreakable(Set.of(cell))) {
                    refused.put(cell, BuildRefusal.NO_WAY_TO_BUILD);
                } else {
                    add(Kind.STRIP, cell, Step.strip(level, cell), null);
                }
            } else if (pours(cell, goal, standing)) {
                add(Kind.POUR, cell, Step.pour(cell, standing), null);
            } else {
                Footprint print = target.footprint(cell);
                if (BuildPlanner.unlayable(print) || !Footprints.formed(print) || unbreakable(print.cells().keySet())) {
                    refused.put(cell, BuildRefusal.NO_WAY_TO_BUILD);
                } else {
                    add(Kind.LAY, cell, Step.lay(level, print), null);
                }
            }
        }
    }

    /**
     * Fluid is let out a layer at a time: all of it on one level that the blueprint wants open and that runs
     * together. Fluid running in from outside what the blueprint clears, beside or above, would only run back.
     */
    private void drain(BlockPos start, Fluid fluid, Set<BlockPos> pending) {
        Map<BlockPos, BlockState> layer = new LinkedHashMap<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        open.add(start);
        layer.put(start, level.getBlockState(start));
        while (!open.isEmpty()) {
            BlockPos cell = open.remove();
            for (Direction direction : Direction.values()) {
                if (direction == Direction.DOWN) {
                    continue;
                }
                BlockPos next = cell.relative(direction);
                BlockState state = level.getBlockState(next);
                if (layer.containsKey(next) || !state.getFluidState().getType().isSame(fluid)) {
                    continue;
                }
                boolean sameLayer = direction != Direction.UP;
                boolean cleared = pending.contains(next) && target.wanted(next).isAir();
                if (sameLayer && cleared && !owner.containsKey(next) && layer.size() < MOST_DRAINED) {
                    layer.put(next, state);
                    open.add(next);
                } else if (!cleared) {
                    layer.keySet().forEach(cellOf -> refused.put(cellOf, BuildRefusal.NO_WAY_TO_BUILD));
                    return;
                }
            }
        }
        add(Kind.DRAIN, start, Step.drain(start, layer), null);
    }

    private void joints() {
        for (Joint joint : target.joints()) {
            Draft join = add(Kind.JOIN, joint.from(), null, joint);
            for (BlockPos end : List.of(joint.from(), joint.to())) {
                Integer laid = owner.get(end);
                if (laid != null && laid != join.id) {
                    wait(join.id, laid);
                }
            }
        }
    }

    private Draft add(Kind kind, BlockPos focus, @Nullable Step step, @Nullable Joint joint) {
        Draft draft = new Draft(drafts.size(), kind, focus.immutable(), step, joint);
        drafts.add(draft);
        if (step != null) {
            step.after().keySet().forEach(cell -> owner.putIfAbsent(cell.immutable(), draft.id));
        }
        return draft;
    }

    private void wait(int id, int before) {
        hard.computeIfAbsent(id, key -> new LinkedHashSet<>()).add(before);
    }

    // What the piece leans on once all is built: every neighbour it would not stay without, taken away alone.
    private void held(Draft draft, Sketch done) {
        for (Map.Entry<BlockPos, BlockState> part : draft.after().entrySet()) {
            BlockState state = part.getValue();
            if (state.isAir() || state.liquid()) {
                continue;
            }
            BlockPos at = part.getKey();
            // Seagrass and the like are laid into water already standing there; nothing pours it for them.
            if (!BuildPlanner.stays(done, at, state)
                || BuildPlanner.drowned(state) && !level.getBlockState(at).getFluidState().isSourceOfType(Fluids.WATER)) {
                draft.dropped = true;
                refused.put(at, BuildRefusal.NO_WAY_TO_BUILD);
                return;
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = at.relative(direction);
                Integer by = owner.get(next);
                if (by == null || by == draft.id || drafts.get(by).kind != Kind.LAY) {
                    continue;
                }
                BlockState was = done.lift(next);
                boolean leans = !BuildPlanner.stays(done, at, state);
                done.restore(next, was);
                if (leans) {
                    wait(draft.id, by);
                }
            }
        }
    }

    // What hangs on a cell cleared goes first, as it would drop when the cell does.
    private void cleared(Draft draft) {
        Sketch without = new Sketch(level);
        without.drawAll(draft.after());
        for (BlockPos part : draft.after().keySet()) {
            for (Direction direction : Direction.values()) {
                BlockPos next = part.relative(direction);
                Integer by = owner.get(next);
                if (by == null || by == draft.id || drafts.get(by).kind != Kind.STRIP) {
                    continue;
                }
                BlockState state = level.getBlockState(next);
                if (!state.isAir() && !state.liquid() && BuildPlanner.stays(level, next, state)
                    && !BuildPlanner.stays(without, next, state)) {
                    wait(draft.id, by);
                }
            }
        }
    }

    // Water is let out from the top down, so the layer over a drained one does not run back into it.
    private void drained(Draft draft) {
        for (BlockPos cell : draft.after().keySet()) {
            Integer over = owner.get(cell.above());
            if (over != null && over != draft.id && drafts.get(over).kind == Kind.DRAIN) {
                wait(draft.id, over);
            }
        }
    }

    /** Water poured waits for the walls it would run out through, every one it would spill past. */
    private void walledIn(Draft draft) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        seen.add(draft.focus);
        open.add(draft.focus);
        while (!open.isEmpty() && seen.size() < MOST_DRAINED) {
            BlockPos at = open.remove();
            for (Direction direction : Direction.values()) {
                BlockPos next = at.relative(direction);
                if (direction == Direction.UP || !seen.add(next) || !target.cells().containsKey(next)) {
                    continue;
                }
                BlockState wanted = target.wanted(next);
                if (wet(wanted)) {
                    open.add(next);
                    continue;
                }
                Integer wall = owner.get(next);
                if (!wanted.isAir() && wall != null && wall != draft.id) {
                    wait(draft.id, wall);
                }
            }
        }
    }

    private record Opening(int plane, LongOpenHashSet outside) {
    }

    /**
     * The plane the build goes out from: the height a body stands at, on the ground the world has now and open to
     * the outside, where the most such places touch the work. That is where the work is come to, not where most of
     * it lies. Failing any, the bottom of the work.
     */
    private Opening opening() {
        BlockPos min = null;
        BlockPos max = null;
        for (BlockPos cell : owner.keySet()) {
            min = min == null ? cell : BlockPos.min(min, cell);
            max = max == null ? cell : BlockPos.max(max, cell);
        }
        if (min == null) {
            return new Opening(target.cells().keySet().stream().mapToInt(BlockPos::getY).min().orElse(0),
                new LongOpenHashSet());
        }
        int lowest = min.getY();
        min = min.offset(-REACH, -REACH, -REACH);
        max = max.offset(REACH, REACH, REACH);
        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (volume > MOST_FLOODED) {
            return new Opening(lowest, new LongOpenHashSet());
        }
        LongOpenHashSet outside = outside(min, max);
        TreeMap<Integer, Integer> come = new TreeMap<>();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        outside.forEach(packed -> {
            BlockPos feet = BlockPos.of(packed);
            if (!outside.contains(feet.above().asLong()) || !floored(feet)) {
                return;
            }
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (owner.containsKey(probe.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz))) {
                            come.merge(feet.getY(), 1, Integer::sum);
                            return;
                        }
                    }
                }
            }
        });
        int plane = lowest;
        int most = 0;
        for (Map.Entry<Integer, Integer> height : come.entrySet()) {
            if (height.getValue() > most) {
                most = height.getValue();
                plane = height.getKey();
            }
        }
        return new Opening(plane, outside);
    }

    private LongOpenHashSet outside(BlockPos min, BlockPos max) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue open = new LongArrayFIFOQueue();
        for (BlockPos cell : BlockPos.betweenClosed(min, max)) {
            boolean edge = cell.getX() == min.getX() || cell.getX() == max.getX() || cell.getY() == min.getY()
                || cell.getY() == max.getY() || cell.getZ() == min.getZ() || cell.getZ() == max.getZ();
            if (edge && open(cell) && seen.add(cell.asLong())) {
                open.enqueue(cell.asLong());
            }
        }
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        while (!open.isEmpty()) {
            BlockPos at = BlockPos.of(open.dequeueLong());
            for (Direction direction : Direction.values()) {
                next.setWithOffset(at, direction);
                if (next.getX() < min.getX() || next.getX() > max.getX() || next.getY() < min.getY()
                    || next.getY() > max.getY() || next.getZ() < min.getZ() || next.getZ() > max.getZ()) {
                    continue;
                }
                if (open(next) && seen.add(next.asLong())) {
                    open.enqueue(next.asLong());
                }
            }
        }
        return seen;
    }

    private boolean open(BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        return state.getFluidState().isEmpty() && state.getCollisionShape(level, cell).isEmpty();
    }

    private boolean floored(BlockPos feet) {
        BlockPos floor = feet.below();
        return level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
    }

    private static int band(Draft draft, int plane) {
        int y = draft.focus.getY();
        return y >= plane ? y - plane : plane - 1 - y;
    }

    /**
     * Gives each piece the one piece it goes out from, spreading through the work from where the plane is open to
     * the outside. A step out to the next layer starts that layer afresh; a step along a layer, or to a piece that
     * hangs back toward the plane (an eave under the ridge it hangs from), comes one after. So a wall goes up course
     * by course, a drift is dug from its mouth inward, and nothing is begun in the air.
     *
     * <p>A piece the spread meets before what holds it up waits for that, and is reached on from it: so it never
     * leads the way to its own support. Work the spread never meets (it touches nothing) starts on its own; a ring of
     * holding, which no world has, is broken where it stops the spread, and counted.
     */
    private int spread(Opening opening, Map<Integer, Long> keys, Map<Integer, Integer> parent) {
        int plane = opening.plane();
        List<Draft> live = live();
        Set<Integer> alive = new HashSet<>();
        live.forEach(draft -> alive.add(draft.id));
        Map<Integer, Integer> waiting = new HashMap<>();
        Map<Integer, List<Integer>> freeing = new HashMap<>();
        hard.forEach((id, befores) -> {
            if (alive.contains(id)) {
                for (int before : befores) {
                    if (alive.contains(before)) {
                        waiting.merge(id, 1, Integer::sum);
                        freeing.computeIfAbsent(before, key -> new ArrayList<>()).add(id);
                    }
                }
            }
        });
        Map<Integer, Long> reached = new HashMap<>();
        Map<Integer, Long> floor = new HashMap<>();
        java.util.PriorityQueue<long[]> open = new java.util.PriorityQueue<>(
            Comparator.<long[]>comparingLong(entry -> entry[0]).thenComparingLong(entry -> entry[1]));
        List<Draft> planed = live.stream().filter(draft -> band(draft, plane) == 0).toList();
        List<Draft> seeds = planed.stream().filter(draft -> touchesOutside(draft, opening.outside())).toList();
        for (Draft draft : seeds.isEmpty() ? planed : seeds) {
            reached.put(draft.id, 0L);
            open.add(new long[] {0L, draft.id});
        }
        Set<Integer> parked = new HashSet<>();
        // Work the spread never meets starts where it lies open to the outside, the nearest the plane first.
        List<Draft> byBand = new ArrayList<>(live);
        byBand.sort(Comparator.<Draft>comparingInt(draft -> touchesOutside(draft, opening.outside()) ? 0 : 1)
            .thenComparingInt(draft -> band(draft, plane)));
        int next = 0;
        int broken = 0;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        while (keys.size() < live.size()) {
            if (open.isEmpty()) {
                Integer stuck = parked.stream().min(Comparator.comparingLong(id -> due(id, reached, floor))).orElse(null);
                if (stuck != null) {
                    broken += waiting.remove(stuck);
                    parked.remove(stuck);
                    open.add(new long[] {due(stuck, reached, floor), stuck});
                } else {
                    while (keys.containsKey(byBand.get(next).id)) {
                        next++;
                    }
                    Draft alone = byBand.get(next);
                    if (reached.containsKey(alone.id)) {
                        open.add(new long[] {due(alone.id, reached, floor), alone.id});
                        continue;
                    }
                    reached.put(alone.id, (long) band(alone, plane) << BAND_SHIFT);
                    open.add(new long[] {reached.get(alone.id), alone.id});
                }
                continue;
            }
            long[] at = open.remove();
            int id = (int) at[1];
            if (keys.containsKey(id) || at[0] != due(id, reached, floor)) {
                continue;
            }
            if (waiting.getOrDefault(id, 0) > 0) {
                parked.add(id);
                continue;
            }
            keys.put(id, at[0]);
            for (int held : freeing.getOrDefault(id, List.of())) {
                floor.merge(held, at[0] + HOP, Math::max);
                if (waiting.merge(held, -1, Integer::sum) == 0 && (parked.remove(held) || reached.containsKey(held))) {
                    open.add(new long[] {due(held, reached, floor), held});
                }
            }
            Draft here = drafts.get(id);
            int band = band(here, plane);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Integer by = owner.get(probe.set(here.focus.getX() + dx, here.focus.getY() + dy,
                            here.focus.getZ() + dz));
                        if (by == null || !alive.contains(by) || keys.containsKey(by)) {
                            continue;
                        }
                        int out = band(drafts.get(by), plane);
                        long key = out > band ? Math.max((long) out << BAND_SHIFT, at[0] + HOP) : at[0] + HOP;
                        Long known = reached.get(by);
                        if (known == null || key < known) {
                            reached.put(by, key);
                            parent.put(by, id);
                            if (!parked.contains(by)) {
                                open.add(new long[] {due(by, reached, floor), by});
                            }
                        }
                    }
                }
            }
        }
        return broken;
    }

    // When a piece comes up: once the spread has reached it, and not before all that holds it up.
    private static long due(int id, Map<Integer, Long> reached, Map<Integer, Long> floor) {
        Long spread = reached.get(id);
        Long held = floor.get(id);
        if (spread == null) {
            return held == null ? Long.MAX_VALUE / 2 : held;
        }
        return held == null ? spread : Math.max(spread, held);
    }

    private static boolean touchesOutside(Draft draft, LongOpenHashSet outside) {
        if (outside.contains(draft.focus.asLong())) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (outside.contains(draft.focus.relative(direction).asLong())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where a body may stand to do a piece, as the ground will be when the piece comes up: every piece before it
     * done, none after, and any scaffold raised for it standing. Any cell in arm's reach counts, walls between or not.
     * With none in reach and no scaffold to be had, the nearest ground under it counts: the piece is done from below,
     * out of reach, rather than never.
     */
    private List<BlockPos> stances(Draft draft, long key) {
        Set<BlockPos> own = draft.after().keySet();
        BlockPos focus = draft.focus;
        List<BlockPos> found = inReach(draft, key);
        for (int dy = -STANCE_DROP; found.isEmpty() && dy >= -FAR_DROP; dy--) {
            for (int dx = -STANCE_SPAN; dx <= STANCE_SPAN; dx++) {
                for (int dz = -STANCE_SPAN; dz <= STANCE_SPAN; dz++) {
                    BlockPos feet = focus.offset(dx, dy, dz);
                    if (!own.contains(feet) && standsAt(feet, key)) {
                        found.add(feet);
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(feet -> feet.distSqr(focus)));
        return List.copyOf(found.subList(0, Math.min(MOST_STANCES, found.size())));
    }

    private List<BlockPos> inReach(Draft draft, long key) {
        Set<BlockPos> own = draft.after().keySet();
        BlockPos focus = draft.focus;
        List<BlockPos> found = new ArrayList<>();
        for (int dy = -STANCE_DROP; dy <= 1; dy++) {
            for (int dx = -STANCE_SPAN; dx <= STANCE_SPAN; dx++) {
                for (int dz = -STANCE_SPAN; dz <= STANCE_SPAN; dz++) {
                    BlockPos feet = focus.offset(dx, dy, dz);
                    if (!own.contains(feet) && Reach.withinReach(feet, focus) && standsAt(feet, key)) {
                        found.add(feet);
                    }
                }
            }
        }
        return found;
    }

    private static final class Scaffold {
        final List<BlockPos> column;
        final BlockPos top;
        final List<Draft> served = new ArrayList<>();
        final long raised;
        long lowered;

        Scaffold(List<BlockPos> column, long raised) {
            this.column = column;
            this.top = column.getLast().above();
            this.raised = raised;
        }
    }

    /**
     * A scaffold for every piece with nowhere in reach to stand: a column beside and under it, raised before it from
     * the ground and lowered once all it serves is done. A scaffold already up serves what else its top reaches, so
     * one column takes a run of a high course; a piece no column can be raised for is done from below.
     */
    private List<Scaffold> scaffold(Map<Integer, Long> keys) {
        List<Scaffold> raised = new ArrayList<>();
        List<Draft> laid = new ArrayList<>(live().stream().filter(draft -> draft.kind == Kind.LAY).toList());
        laid.sort(Comparator.comparingLong(draft -> keys.get(draft.id)));
        for (Draft draft : laid) {
            long key = keys.get(draft.id);
            if (!inReach(draft, key).isEmpty()) {
                continue;
            }
            Scaffold up = null;
            for (Scaffold one : raised) {
                if (one.served.size() < MOST_FROM_ONE_SCAFFOLD && one.raised < key && reaches(one.top, draft)) {
                    up = one;
                    break;
                }
            }
            if (up == null) {
                up = column(draft, key);
                if (up == null) {
                    continue;
                }
                raised.add(up);
            }
            up.served.add(draft);
            up.lowered = Math.max(up.lowered, key + HOP);
            long[] span = {up.raised, up.lowered};
            up.column.forEach(cell -> scaffolded.put(cell, span));
        }
        return raised;
    }

    // The nearest column that stands on ground nothing changes, through air nothing fills, high enough for its top
    // to reach the whole piece, and that a body on the ground beside its foot can raise.
    private Scaffold column(Draft draft, long key) {
        List<int[]> around = new ArrayList<>();
        for (int dx = -SCAFFOLD_SPREAD; dx <= SCAFFOLD_SPREAD; dx++) {
            for (int dz = -SCAFFOLD_SPREAD; dz <= SCAFFOLD_SPREAD; dz++) {
                around.add(new int[] {dx, dz});
            }
        }
        around.sort(Comparator.comparingInt(at -> Math.abs(at[0]) + Math.abs(at[1])));
        for (int[] at : around) {
            List<BlockPos> column = column(draft.focus.offset(at[0], -SCAFFOLD_BELOW, at[1]));
            if (column.isEmpty()) {
                continue;
            }
            Scaffold scaffold = new Scaffold(column, key - HOP);
            if (!reaches(scaffold.top, draft) || !clear(scaffold.top, key) || !clear(scaffold.top.above(), key)
                || owner.containsKey(scaffold.top) || owner.containsKey(scaffold.top.above())) {
                continue;
            }
            Draft raising = new Draft(-1, Kind.SCAFFOLD, column.getFirst(), Step.raise(column), null);
            if (!inReach(raising, scaffold.raised).isEmpty()) {
                return scaffold;
            }
        }
        return null;
    }

    // Downward from the top cell, to the first floor: never onto or through anything a piece changes, nor onto
    // another scaffold, which drops what stands on it when it comes down. Bottom first, as it goes up.
    private List<BlockPos> column(BlockPos top) {
        List<BlockPos> downward = new ArrayList<>();
        for (BlockPos cell = top; downward.size() < TALLEST_SCAFFOLD; cell = cell.below()) {
            if (!level.getBlockState(cell).isAir() || owner.containsKey(cell) || scaffolded.containsKey(cell)) {
                return List.of();
            }
            downward.add(cell);
            BlockPos floor = cell.below();
            if (!owner.containsKey(floor) && !scaffolded.containsKey(floor)
                && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
                return downward.size() < 2 ? List.of() : List.copyOf(downward.reversed());
            }
        }
        return List.of();
    }

    private static boolean reaches(BlockPos feet, Draft draft) {
        for (BlockPos cell : draft.after().keySet()) {
            if (!Reach.withinReach(feet, cell)) {
                return false;
            }
        }
        return true;
    }

    private boolean standsAt(BlockPos feet, long key) {
        return clear(feet, key) && clear(feet.above(), key) && floor(feet.below(), key);
    }

    private BlockState stateAt(BlockPos cell, long key) {
        long[] span = scaffolded.get(cell);
        if (span != null && span[0] < key && key <= span[1]) {
            return ScaffoldNode.state();
        }
        Long at = changedAt.get(cell);
        return at != null && at < key ? changedTo.get(cell) : level.getBlockState(cell);
    }

    private boolean clear(BlockPos cell, long key) {
        BlockState state = stateAt(cell, key);
        return state.getCollisionShape(level, cell).isEmpty() && !state.getFluidState().is(FluidTags.LAVA);
    }

    private boolean floor(BlockPos cell, long key) {
        return stateAt(cell, key).isFaceSturdy(level, cell, Direction.UP);
    }

    private boolean unbreakable(Set<BlockPos> cells) {
        for (BlockPos cell : cells) {
            for (Map.Entry<BlockPos, BlockState> part : Footprints.standing(level, cell).entrySet()) {
                BlockState state = part.getValue();
                if (!state.isAir() && !state.liquid() && state.getDestroySpeed(level, part.getKey()) < 0.0F) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean pours(BlockPos cell, BlockState goal, BlockState standing) {
        return goal.hasProperty(BlockStateProperties.WATERLOGGED) && goal.getValue(BlockStateProperties.WATERLOGGED)
            && Fidelity.same(standing, goal.setValue(BlockStateProperties.WATERLOGGED, false));
    }

    private static boolean wet(BlockState state) {
        return state.getBlock() instanceof LiquidBlock
            || state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
    }
}
