package io.github.izakyl.folkways.plugins.combat.group;

import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** Transient logical captain. Only the existing command runner moves residents. */
final class PointRoute {
    final UUID commandId = UUID.randomUUID();
    final boolean attackMove;
    private static final int ADVANCE = 6;
    private static final int SLOT_PATH_ATTEMPTS = 12;
    private static final int MAX_POINTS = 16;
    List<WorldPos> points;
    int cycleStart;
    boolean appendable;
    private final UnitGroups owner;
    private WorldPos captain;
    private int stop;
    private int travellingTicks;
    private boolean complete;

    PointRoute(UnitGroups owner, List<Body> bodies, List<WorldPos> points, int cycleStart) {
        this(owner, bodies, points, cycleStart, false);
    }

    PointRoute(UnitGroups owner, List<Body> bodies, List<WorldPos> points, int cycleStart, boolean appendable) {
        this(owner, bodies, points, cycleStart, appendable, false);
    }

    PointRoute(UnitGroups owner, List<Body> bodies, List<WorldPos> points, int cycleStart, boolean appendable, boolean attackMove) {
        this.owner = owner;
        this.attackMove = attackMove;
        this.points = List.copyOf(points);
        this.cycleStart = cycleStart;
        this.appendable = appendable;
        if (bodies.isEmpty() || points.isEmpty() || points.size() > MAX_POINTS
                || (cycleStart == -1 ? points.size() != 1 : cycleStart < 0 || cycleStart >= points.size() - 1)
                || new HashSet<>(points).size() != points.size()) {
            throw new IllegalArgumentException("invalid point route or cycle");
        }
        // Preflight every mandatory stop before replacing any member's current command.
        for (WorldPos point : points) {
            if (!points.getFirst().sameRealm(point)) { throw new IllegalArgumentException("route crosses realms"); }
            slots(bodies, point);
        }
        WorldPos first = WorldSpaces.at(bodies.getFirst().mob());
        double x = bodies.stream().map(body -> WorldSpaces.at(body.mob())).mapToInt(at -> at.cell().getX()).average().orElseThrow();
        double y = bodies.stream().map(body -> WorldSpaces.at(body.mob())).mapToInt(at -> at.cell().getY()).average().orElseThrow();
        double z = bodies.stream().map(body -> WorldSpaces.at(body.mob())).mapToInt(at -> at.cell().getZ()).average().orElseThrow();
        WorldPos center = first.at(BlockPos.containing(x, y, z));
        captain = first;
        for (int dy : new int[] {0, -1, 1, -2, 2}) {
            WorldPos candidate = center.at(center.cell().offset(0, dy, 0));
            try { slots(bodies, candidate); captain = candidate; break; }
            catch (IllegalArgumentException unavailable) { /* Gather around a loaded member if the midpoint is unsafe. */ }
        }
        slots(bodies, captain);
    }

    WorldPos captain() { return captain; }
    int stop() { return stop; }
    boolean atStop() { return captain.equals(points.get(stop)); }
    boolean loops() { return cycleStart >= 0; }
    boolean hasNext() { return stop + 1 < points.size() || loops(); }
    boolean complete() { return complete && !loops(); }
    void completed() { complete = !loops(); }
    List<WorldPos> slots(List<Body> bodies) { return slots(bodies, captain); }

    /** Validate first; appending must never reassemble or rewind an already travelling squad. */
    void append(List<Body> bodies, WorldPos point) {
        if (loops() || bodies.isEmpty()) {
            throw new IllegalArgumentException("this point queue is not open");
        }
        if (!points.getFirst().sameRealm(point)) { throw new IllegalArgumentException("route crosses realms"); }
        int existing = points.indexOf(point);
        if (existing >= 0 && existing >= points.size() - 1) {
            throw new IllegalArgumentException("a cycle requires at least two distinct points");
        }
        if (existing < 0 && points.size() >= MAX_POINTS) {
            throw new IllegalArgumentException("point queues allow at most sixteen distinct points");
        }
        slots(bodies, point);
        if (existing >= 0) { cycleStart = existing; }
        else {
            List<WorldPos> next = new ArrayList<>(points);
            next.add(point);
            points = List.copyOf(next);
        }
        appendable = true;
        complete = false;
    }

    /** Compute without changing the captain; commit only after a complete command batch is accepted. */
    Optional<WorldPos> advance(List<Body> bodies) {
        WorldPos goal = points.get(stop);
        Body leader = bodies.getFirst();
        var level = leader.mob().level();
        if (Reach.standingAt(WorldSpaces.relative(level, goal.realm(), leader.mob().position()).orElseThrow(), goal.cell())) {
            return Optional.of(goal);
        }
        var path = leader.mob().getNavigation().createPath(Set.of(goal.block(level)), 0);
        // A temporary airborne body cannot update its ground navigator. A distant target may
        // also have a useful partial path inside FOLLOW_RANGE; neither means permanent failure.
        if (path == null) { return Optional.empty(); }
        List<WorldPos> candidates = new ArrayList<>();
        Vec3 previous = WorldSpaces.relative(level, goal.realm(), leader.mob().position()).orElseThrow();
        double alreadyCovered = previous.distanceTo(Vec3.atBottomCenterOf(captain.cell()));
        double length = 0;
        // createPath can reuse a running path. Start at its live cursor, using the same real
        // resident as the path finder, and bound cumulative path length rather than an anchor sphere.
        for (int n = path.getNextNodeIndex(); n < path.getNodeCount(); n++) {
            var node = path.getNode(n);
            WorldPos point = WorldPos.of(level, new BlockPos(node.x, node.y, node.z));
            if (!goal.sameRealm(point)) { break; }
            Vec3 feet = Vec3.atBottomCenterOf(point.cell());
            length += feet.distanceTo(previous);
            if (length > ADVANCE + .01) { break; }
            previous = feet;
            if (!point.equals(captain) && (point.equals(goal) || length > alreadyCovered + .5)) { candidates.add(point); }
        }
        for (WorldPos candidate : candidates.reversed()) {
            try { slots(bodies, candidate); return Optional.of(candidate); }
            catch (IllegalArgumentException unavailable) { /* A doorway node need not itself become an assembly stop. */ }
        }
        return Optional.empty();
    }

    void advanced(WorldPos next) { captain = next; }
    int travellingTicks() { return travellingTicks; }
    /** Called only while a present squad owns the runner and is travelling or recovering. */
    void travelling() { travellingTicks++; }
    void nextStop() {
        if (!hasNext()) { throw new IllegalStateException("point queue is waiting for another point"); }
        stop = stop + 1 < points.size() ? stop + 1 : cycleStart;
        travellingTicks = 0;
        complete = false;
    }

    /** A bounded local search for a safe end-of-command assembly anchor. */
    static PointRoute regroup(UnitGroups owner, List<Body> bodies) {
        for (Body body : bodies) {
            WorldPos at = WorldSpaces.at(body.mob());
            for (int radius = 0; radius <= 2; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) { continue; }
                        for (int dy : new int[] {0, -1, 1, -2, 2}) {
                            try {
                                PointRoute route = new PointRoute(owner, bodies,
                                    List.of(at.at(at.cell().offset(dx, dy, dz))), -1);
                                route.captain = route.points.getFirst();
                                route.slots(bodies);
                                return route;
                            } catch (IllegalArgumentException unavailable) { /* Try the next local dry anchor. */ }
                        }
                    }
                }
            }
        }
        throw new IllegalArgumentException("no safe local regroup anchor");
    }

    Vec3 front() {
        Vec3 toward = Vec3.atLowerCornerOf(points.get(stop).cell()).subtract(Vec3.atLowerCornerOf(captain.cell()));
        return toward.horizontalDistanceSqr() < .01 ? new Vec3(1, 0, 0) : new Vec3(toward.x, 0, toward.z).normalize();
    }

    /** Compact, connected dry standing cells, with local rise/drop instead of an exact flat rectangle. */
    List<WorldPos> slots(List<Body> bodies, WorldPos anchor) {
        return slots(bodies, anchor, Map.of(), Set.of(), Set.of());
    }

    /** Path probes run only when installing a batch or recovering a blocked member. */
    List<WorldPos> slots(List<Body> bodies, WorldPos anchor, Map<UUID, WorldPos> previous,
            Set<UUID> checkPaths, Set<UUID> alternatives) {
        if (bodies.isEmpty()) { return List.of(); }
        Body first = bodies.getFirst();
        owner.commands.validate(first, anchor);
        if (!safe(first, anchor)) { throw new IllegalArgumentException("captain anchor has no safe footing"); }
        int radius = Math.max(3, (int) Math.ceil(Math.sqrt(bodies.size())));
        Set<WorldPos> connected = new LinkedHashSet<>();
        ArrayDeque<WorldPos> queue = new ArrayDeque<>();
        connected.add(anchor);
        queue.add(anchor);
        while (!queue.isEmpty()) {
            WorldPos from = queue.removeFirst();
            for (int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int dy : new int[] {0, -1, 1}) {
                    WorldPos candidate = from.at(from.cell().offset(direction[0], dy, direction[1]));
                    BlockPos offset = candidate.cell().subtract(anchor.cell());
                    if (Math.abs(offset.getX()) > radius || Math.abs(offset.getZ()) > radius
                            || Math.abs(offset.getY()) > 2 || connected.contains(candidate) || !safe(first, candidate)) { continue; }
                    connected.add(candidate);
                    queue.add(candidate);
                    break;
                }
            }
        }
        List<BlockPos> preferred = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += 1) {
            for (int dz = -radius; dz <= radius; dz += 1) {
                if (dx % 2 == 0 && dz % 2 == 0) { preferred.add(new BlockPos(dx, 0, dz)); }
            }
        }
        preferred.sort(Comparator.comparingDouble(offset -> offset.distSqr(BlockPos.ZERO)));
        List<WorldPos> available = new ArrayList<>(connected);
        List<WorldPos> result = new ArrayList<>();
        List<AABB> occupied = new ArrayList<>();
        for (int n = 0; n < bodies.size(); n++) {
            Body body = bodies.get(n);
            WorldPos desired = anchor.at(anchor.cell().offset(preferred.get(n)));
            WorldPos old = previous.get(body.id());
            if (!alternatives.isEmpty() && !alternatives.contains(body.id())) {
                // Recovery replaces only blocked handles. Every healthy handle keeps its exact
                // existing target, even if changed terrain now makes that target unsafe.
                if (old == null) { throw new Unavailable(body.id()); }
                AABB box = at(body, old);
                if (occupied.stream().anyMatch(box::intersects)) { throw new Unavailable(body.id()); }
                result.add(old);
                occupied.add(box);
                available.remove(old);
                continue;
            }
            if (old != null && captain != null && old.sameRealm(captain)) {
                // Translate an existing member's offset instead of changing sides at each bend.
                desired = anchor.at(anchor.cell().offset(old.cell().subtract(captain.cell())));
            }
            if (n == 0) { desired = anchor; }
            final WorldPos wanted = desired;
            available.sort(Comparator.<WorldPos>comparingInt(at ->
                    alternatives.contains(body.id()) && nontouching(at, old) ? 0 : 1)
                .thenComparingDouble(at -> at.cell().distSqr(wanted.cell()))
                .thenComparingDouble(at -> at.cell().distSqr(WorldSpaces.at(body.mob()).cell())));
            WorldPos chosen = null;
            int attempts = 0;
            for (WorldPos candidate : available) {
                // The reference member must physically visit the authored click itself.
                if (n == 0 && !candidate.equals(anchor)) { continue; }
                // A recovering member may change its own slot, but must not displace a healthy
                // member whose existing runner is still travelling to a later entry in this batch.
                AABB box = at(body, candidate);
                if (alternatives.contains(body.id()) && bodies.stream().anyMatch(other ->
                        !other.id().equals(body.id()) && !alternatives.contains(other.id())
                            && previous.containsKey(other.id()) && box.intersects(at(other, previous.get(other.id()))))) { continue; }
                try { owner.commands.validate(body, candidate); }
                catch (IllegalArgumentException unavailable) { continue; }
                if (!safe(body, candidate) || occupied.stream().anyMatch(box::intersects)) { continue; }
                if (checkPaths.contains(body.id()) && !Reach.standingAt(
                        WorldSpaces.relative(body.mob().level(), candidate.realm(), body.mob().position()).orElseThrow(), candidate.cell())) {
                    if (++attempts > SLOT_PATH_ATTEMPTS) { break; }
                    var path = body.mob().getNavigation().createPath(Set.of(candidate.block(body.mob().level())), 0);
                    if (path == null || !path.canReach()) { continue; }
                }
                chosen = candidate;
                break;
            }
            if (chosen == null) { throw new Unavailable(body.id()); }
            result.add(chosen);
            occupied.add(at(body, chosen));
            available.remove(chosen);
        }
        return List.copyOf(result);
    }

    private static boolean nontouching(WorldPos candidate, WorldPos old) {
        return old == null || !candidate.equals(old);
    }

    private static AABB at(Body body, WorldPos point) {
        Vec3 world = WorldSpaces.world(body.mob().level(), point).orElseThrow();
        return body.mob().getBoundingBox().move(world.subtract(body.mob().position())).inflate(.05);
    }

    static final class Unavailable extends IllegalArgumentException {
        final UUID member;
        Unavailable(UUID member) { super("no reachable non-overlapping nearby squad slot for " + member); this.member = member; }
    }

    private static boolean safe(Body body, WorldPos point) {
        var level = body.mob().level();
        if (!point.sameRealm(WorldSpaces.at(body.mob())) || !point.in(level)) { return false; }
        BlockPos feet = point.block(level);
        return level.hasChunksAt(feet.offset(-1, -1, -1), feet.offset(1, 2, 1))
            && Footing.withFeetAt(level, feet) && level.getFluidState(feet).isEmpty()
            && level.getFluidState(feet.above()).isEmpty()
            && WalkNodeEvaluator.getPathTypeStatic(new PathfindingContext(level, body.mob()), feet.mutable()) == PathType.WALKABLE;
    }
}
