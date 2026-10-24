package io.github.izakyl.folkways.plugins.combat.group;

import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

/** Transient logical captain. Only the existing command runner moves residents. */
final class PointRoute {
    final UUID commandId = UUID.randomUUID();
    private static final int ADVANCE = 3;
    private static final int MAX_POINTS = 16;
    List<WorldPos> points;
    int cycleStart;
    boolean appendable;
    private final UnitGroups owner;
    private WorldPos captain;
    private int stop;
    private long travellingSince;

    PointRoute(UnitGroups owner, List<Body> bodies, List<WorldPos> points, int cycleStart) {
        this(owner, bodies, points, cycleStart, false);
    }

    PointRoute(UnitGroups owner, List<Body> bodies, List<WorldPos> points, int cycleStart, boolean appendable) {
        this.owner = owner;
        this.points = List.copyOf(points);
        this.cycleStart = cycleStart;
        this.appendable = appendable;
        travellingSince = owner.server.overworld().getGameTime();
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
    }

    /** Compute without changing the captain; commit only after a complete command batch is accepted. */
    WorldPos advance(List<Body> bodies) {
        if (travellingTicks() >= 2400) {
            throw new IllegalArgumentException("captain route leg timed out");
        }
        WorldPos goal = points.get(stop);
        Body leader = bodies.getFirst(); // Its assigned slot is the captain itself, never a distant real leader.
        var level = leader.mob().level();
        var path = leader.mob().getNavigation().createPath(goal.block(level), 0);
        if (path == null || !path.canReach()) { throw new IllegalArgumentException("captain has no route to waypoint"); }
        List<WorldPos> candidates = new ArrayList<>();
        // Read a short prefix of the existing navigator's path; never start another navigator or steer it here.
        for (int n = 0; n < path.getNodeCount(); n++) {
            var node = path.getNode(n);
            WorldPos point = WorldPos.of(level, new BlockPos(node.x, node.y, node.z));
            if (!captain.sameRealm(point) || captain.cell().distSqr(point.cell()) > ADVANCE * ADVANCE) { break; }
            if (!point.equals(captain)) { candidates.add(point); }
        }
        for (WorldPos candidate : candidates.reversed()) {
            try { slots(bodies, candidate); return candidate; }
            catch (IllegalArgumentException unavailable) { /* A doorway node need not itself become an assembly stop. */ }
        }
        throw new IllegalArgumentException("captain cannot make a bounded advance");
    }

    void advanced(WorldPos next) { captain = next; }
    long travellingTicks() { return owner.server.overworld().getGameTime() - travellingSince; }
    /** An unavailable squad or an active combat lease owns none of this route's travel budget. */
    void waiting() { travellingSince++; }
    void nextStop() {
        if (!hasNext()) { throw new IllegalStateException("point queue is waiting for another point"); }
        stop = stop + 1 < points.size() ? stop + 1 : cycleStart;
        travellingSince = owner.server.overworld().getGameTime();
    }

    Vec3 front() {
        Vec3 toward = Vec3.atLowerCornerOf(points.get(stop).cell()).subtract(Vec3.atLowerCornerOf(captain.cell()));
        return toward.horizontalDistanceSqr() < .01 ? new Vec3(1, 0, 0) : new Vec3(toward.x, 0, toward.z).normalize();
    }

    /** Compact, connected dry standing cells, with local rise/drop instead of an exact flat rectangle. */
    List<WorldPos> slots(List<Body> bodies, WorldPos anchor) {
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
        for (int n = 0; n < bodies.size(); n++) {
            Body body = bodies.get(n);
            WorldPos desired = anchor.at(anchor.cell().offset(preferred.get(n)));
            available.sort(Comparator.comparingDouble(at -> at.cell().distSqr(desired.cell())));
            WorldPos chosen = null;
            for (WorldPos candidate : available) {
                try { owner.commands.validate(body, candidate); }
                catch (IllegalArgumentException unavailable) { continue; }
                if (safe(body, candidate)) { chosen = candidate; break; }
            }
            if (chosen == null) { throw new IllegalArgumentException("not enough nearby safe squad slots"); }
            result.add(chosen);
            available.remove(chosen);
        }
        return List.copyOf(result);
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
