package io.github.izakyl.folkways.plugins.combat.group;

import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

public final class UnitGroup {
    public enum Phase { IDLE, COLUMN, LINE, HOLDING, ENGAGING, WAITING, FAILED, REFORMING, WAITING_FOR_MEMBER, FAILED_WAITING }
    public enum CommandKind { NONE, DEPLOYMENT, DIRECT, ATTACK_MOVE, ATTACK, STOP }
    public enum EndReason { NONE, TARGET_DEFEATED, TARGET_LOST, OUT_OF_RANGE, TIMED_OUT, AUTHORITY_LOST,
        REPLACED, CANCELLED, FAILED, PARTIAL_FAILURE, NO_SURVIVORS, NO_SAFE_ANCHOR, REGROUP_TIMED_OUT, STOPPED }

    private static final int PATROL_PAUSE = 20;

    private final UnitGroups owner;
    private final UUID id;
    final Set<UUID> members = new LinkedHashSet<>();
    private final Map<UUID, ResidentCommands.Order> orders = new LinkedHashMap<>();
    private Deployment deployment;
    private Phase phase = Phase.IDLE;
    private List<WorldPos> column = List.of();
    private int columnAt;
    private boolean adjustedColumn;
    private List<Deployment> patrol = List.of();
    private int patrolAt = -1;
    private long assembledSince = Long.MIN_VALUE;
    private ResidentCommands.Mode mode = ResidentCommands.Mode.HOLD;
    private PointRoute points;
    private UUID commandId;
    private CommandKind commandKind = CommandKind.NONE;
    private EndReason endReason = EndReason.NONE;
    private boolean attacking;
    private boolean regrouping;
    private int regroupTicks;
    private long attackStarted;
    private boolean pointsCombatPaused;
    private final Set<UUID> failedMembers = new LinkedHashSet<>();
    boolean changed;

    UnitGroup(UnitGroups owner, UUID id) { this.owner = owner; this.id = id; }

    public UUID id() { return id; }
    public Set<UUID> members() { return Set.copyOf(members); }
    public Phase phase() {
        if (phase == Phase.FAILED_WAITING) { return phase; }
        if (orders.values().stream().anyMatch(ResidentCommands.Order::yieldingToDanger)) { return Phase.WAITING_FOR_MEMBER; }
        if (attacking) { return Phase.ENGAGING; }
        if (orders.values().stream().anyMatch(ResidentCommands.Order::engaged)) {
            return Phase.ENGAGING;
        }
        // Entity movement can occur after this group's tick. Never expose a cached assembly
        // while a survivor has already left its assigned slot or yielded the command runner.
        return phase == Phase.HOLDING && !assembled(loaded()) ? Phase.LINE : phase;
    }
    /** Transient route and current stop; membership alone is persisted. */
    public List<Deployment> patrolRoute() { return patrol; }
    public int patrolStop() { return points == null ? patrolAt : points.stop(); }
    /** Mandatory click points, including any one-time prefix before the cycle. */
    public List<WorldPos> pointRoute() { return points == null ? List.of() : points.points; }
    /** One authored point command, stable across appended points and internally replaced leg handles. */
    public Optional<UUID> pointCommandId() { return Optional.ofNullable(points).map(route -> route.commandId); }
    /** Generation changes for each external command, including page deployments, but never for an internal leg. */
    public Optional<UUID> commandId() { return Optional.ofNullable(commandId); }
    public CommandKind commandKind() { return commandKind; }
    public EndReason endReason() { return endReason; }
    public Set<UUID> failedMembers() { return Set.copyOf(failedMembers); }
    public boolean attackMove() { return points != null && points.attackMove; }
    public int cycleStart() { return points == null ? -1 : points.cycleStart; }
    /** An accepted live queue can wait indefinitely at its last point for another click. */
    public boolean pointQueueOpen() {
        return points != null && points.appendable && !points.loops()
            && phase != Phase.IDLE && phase != Phase.FAILED && phase != Phase.FAILED_WAITING;
    }
    public Optional<WorldPos> captain() { return Optional.ofNullable(points).map(PointRoute::captain); }
    public Set<UUID> missing() {
        owner.requireGroup(this);
        Set<UUID> missing = new LinkedHashSet<>(members);
        loaded().forEach(body -> missing.remove(body.id()));
        return Set.copyOf(missing);
    }
    public Map<UUID, WorldPos> destinations() {
        Map<UUID, WorldPos> destinations = new LinkedHashMap<>();
        orders.forEach((member, order) -> destinations.put(member, order.destination()));
        return Map.copyOf(destinations);
    }
    public void add(Body body) { owner.add(this, body); }
    public void remove(UUID member) { owner.remove(this, member); }

    /** Move as a column, then restore the requested line. Holds until release. */
    public void move(Deployment intent) { deploy(intent, true); }
    /** Establish a line at the given endpoints without a preliminary column. */
    public void hold(Deployment intent) { deploy(intent, false); }
    /** Establish one interruptible guard formation, returning to its slots after combat or fear. */
    public void guard(Deployment intent) {
        begin(intent, false, ResidentCommands.Mode.GUARD);
        authored(CommandKind.DEPLOYMENT, UUID.randomUUID());
        points = null;
        patrol = List.of();
        patrolAt = -1;
    }
    /** Retreat as one actual squad; release and later deployments retain ownership of every retreat handle. */
    public void retreat(Deployment intent) {
        begin(intent, false, ResidentCommands.Mode.RETREAT);
        authored(CommandKind.DEPLOYMENT, UUID.randomUUID());
        points = null;
        patrol = List.of();
        patrolAt = -1;
    }

    /** Cycle through two to sixteen formations, assembling before leaving each stop. */
    public void patrol(List<Deployment> route) {
        owner.requireGroup(this);
        List<Deployment> next = List.copyOf(route);
        if (next.size() < 2 || next.size() > 16) {
            throw new IllegalArgumentException("a patrol needs two to sixteen stops");
        }
        // Reject the whole route before replacing any current member order.
        for (Deployment stop : next) {
            if (!next.getFirst().left().sameRealm(stop.left())) {
                throw new IllegalArgumentException("patrol stops must be in the same realm");
            }
            List<Body> bodies = loaded(stop);
            List<WorldPos> cells = stop.line(Math.max(1, bodies.size()));
            validate(bodies, cells);
        }
        begin(next.getFirst(), true, ResidentCommands.Mode.GUARD);
        authored(CommandKind.DEPLOYMENT, UUID.randomUUID());
        points = null;
        patrol = next;
        patrolAt = 0;
    }

    public void gather(WorldPos center, Vec3 front) {
        owner.requireGroup(this);
        Vec3 facing = new Vec3(front.x, 0, front.z).normalize();
        double half = Math.max(1, (members.size() - 1) + 1);
        Vec3 sideways = new Vec3(-facing.z, 0, facing.x).scale(half);
        Vec3 mid = Vec3.atBottomCenterOf(center.cell());
        move(new Deployment(center.at(net.minecraft.core.BlockPos.containing(mid.subtract(sideways))),
            center.at(net.minecraft.core.BlockPos.containing(mid.add(sideways))), facing, new Deployment.Formation(2)));
    }

    private void deploy(Deployment intent, boolean column) {
        begin(intent, column, ResidentCommands.Mode.HOLD);
        authored(CommandKind.DEPLOYMENT, UUID.randomUUID());
        points = null;
        patrol = List.of();
        patrolAt = -1;
    }

    private void begin(Deployment intent, boolean column, ResidentCommands.Mode mode) {
        owner.requireGroup(this);
        List<Body> bodies = loaded(intent);
        if (bodies.isEmpty()) {
            orders.values().forEach(ResidentCommands.Order::close);
            orders.clear();
            deployment = intent;
            this.mode = mode;
            phase = Phase.WAITING;
            assembledSince = Long.MIN_VALUE;
            adjustedColumn = false;
            return;
        }
        List<WorldPos> line = intent.line(bodies.size());
        validate(bodies, line);
        List<WorldPos> cells = line;
        if (column) {
            try { cells = terrainColumn(bodies, intent.column(bodies.size())); }
            catch (IllegalArgumentException unavailable) { cells = line; column = false; }
        }
        deployment = intent;
        this.mode = mode;
        adjustedColumn = column && !cells.equals(intent.column(bodies.size()));
        issue(bodies, cells, column ? Phase.COLUMN : Phase.LINE, mode);
    }

    private void authored(CommandKind kind, UUID generation) {
        commandId = generation;
        commandKind = kind;
        endReason = EndReason.NONE;
        failedMembers.clear();
        attacking = false;
        regrouping = false;
        regroupTicks = 0;
        pointsCombatPaused = false;
    }

    void startAttack(List<ResidentCommands.Order> attacks) {
        owner.requireGroup(this);
        authored(CommandKind.ATTACK, UUID.randomUUID());
        clearRoute();
        orders.values().forEach(ResidentCommands.Order::close);
        orders.clear();
        attacks.forEach(order -> orders.put(order.resident(), order.allowDangerRetreat()));
        attacking = true;
        attackStarted = owner.server.overworld().getGameTime();
        phase = Phase.ENGAGING;
    }

    void stop(List<Body> bodies) {
        owner.requireGroup(this);
        authored(CommandKind.STOP, UUID.randomUUID());
        clearRoute();
        endReason = EndReason.STOPPED;
        regroup(bodies);
    }

    private void clearRoute() {
        points = null;
        patrol = List.of();
        patrolAt = -1;
        deployment = null;
        assembledSince = Long.MIN_VALUE;
    }

    private void tickAttack(List<Body> bodies) {
        long elapsed = owner.server.overworld().getGameTime() - attackStarted;
        for (var entry : orders.entrySet()) {
            ResidentCommands.Order order = entry.getValue();
            // A runner refusal can race the target's loss or bounds check. Its terminal reason
            // is more specific than Status.FAILED and must survive the squad's aggregation.
            if (order.attackEnd() == ResidentCommands.AttackEnd.FAILED
                    || order.attackEnd() == ResidentCommands.AttackEnd.NONE
                        && order.status() == ResidentCommands.Status.FAILED) {
                failedMembers.add(entry.getKey());
            }
        }
        boolean finished = orders.values().stream().allMatch(order ->
            order.status() == ResidentCommands.Status.RELEASED || order.status() == ResidentCommands.Status.FAILED
                || !order.attackEnd().name().equals("NONE"));
        if (!finished && elapsed < 600) { return; }
        endReason = !finished ? EndReason.TIMED_OUT : orders.values().stream()
            .map(order -> attackReason(order.attackEnd().name())).filter(reason -> reason != EndReason.NONE)
            .reduce(EndReason.NONE, (left, right) -> left == EndReason.NONE || left == EndReason.TARGET_DEFEATED ? right : left);
        if (!failedMembers.isEmpty()) {
            endReason = failedMembers.size() == orders.size() ? EndReason.FAILED : EndReason.PARTIAL_FAILURE;
        }
        if (endReason == EndReason.NONE) { endReason = EndReason.CANCELLED; }
        attacking = false;
        regroup(bodies);
    }

    private static EndReason attackReason(String name) {
        try { return EndReason.valueOf(name); }
        catch (IllegalArgumentException unknown) { return EndReason.FAILED; }
    }

    private void regroup(List<Body> bodies) {
        orders.values().forEach(ResidentCommands.Order::close);
        orders.clear();
        regrouping = true;
        regroupTicks = 0;
        if (bodies.isEmpty()) { failedWaiting(EndReason.NO_SURVIVORS); return; }
        try {
            PointRoute anchor = PointRoute.regroup(owner, bodies);
            deployment = new Deployment(anchor.captain(), anchor.captain(), anchor.front(), new Deployment.Formation(2, 1));
            mode = ResidentCommands.Mode.HOLD;
            issue(bodies, anchor.slots(bodies), Phase.REFORMING, mode);
            orders.values().forEach(ResidentCommands.Order::allowDangerRetreat);
        } catch (IllegalArgumentException unavailable) { failedWaiting(EndReason.NO_SAFE_ANCHOR); }
    }

    private void tickRegroup(List<Body> bodies) {
        if (bodies.isEmpty()) { failedWaiting(EndReason.NO_SURVIVORS); return; }
        // Fear and an asynchronously closing native weapon session retain their existing runner;
        // do not replace or pull back those members while they yield to danger.
        if (orders.values().stream().anyMatch(ResidentCommands.Order::yieldingToDanger)) {
            phase = Phase.WAITING_FOR_MEMBER;
            assembledSince = Long.MIN_VALUE;
            return;
        }
        if (orders.values().stream().anyMatch(ResidentCommands.Order::engaged)) {
            phase = Phase.ENGAGING;
            assembledSince = Long.MIN_VALUE;
            return;
        }
        if (++regroupTicks >= 1200) { failedWaiting(EndReason.REGROUP_TIMED_OUT); return; }
        if (orders.values().stream().anyMatch(order -> order.status() == ResidentCommands.Status.FAILED)) {
            orders.forEach((id, order) -> { if (order.status() == ResidentCommands.Status.FAILED) { failedMembers.add(id); } });
            failedWaiting(EndReason.FAILED);
            return;
        }
        if (changed || bodies.size() != orders.size()) {
            // Preserve the safe anchor and existing slots; only newcomers require a fresh local assembly.
            changed = false;
            if (bodies.stream().anyMatch(body -> !orders.containsKey(body.id()) || !orders.get(body.id()).boundTo(body))) {
                try {
                    PointRoute anchor = new PointRoute(owner, bodies, List.of(deployment.left()), -1);
                    issue(bodies, anchor.slots(bodies, deployment.left()), Phase.REFORMING, mode);
                    orders.values().forEach(ResidentCommands.Order::allowDangerRetreat);
                } catch (IllegalArgumentException unavailable) { failedWaiting(EndReason.NO_SAFE_ANCHOR); }
                return;
            }
        }
        if (bodies.size() != members.size() || !assembled(bodies)
                || orders.values().stream().anyMatch(ResidentCommands.Order::engaged)) {
            phase = bodies.size() != members.size() ? Phase.WAITING_FOR_MEMBER : Phase.REFORMING;
            assembledSince = Long.MIN_VALUE;
            return;
        }
        long now = owner.server.overworld().getGameTime();
        if (assembledSince == Long.MIN_VALUE) { assembledSince = now; }
        if (now - assembledSince < PATROL_PAUSE) { phase = Phase.REFORMING; return; }
        regrouping = false;
        phase = Phase.HOLDING;
    }

    private void failedWaiting(EndReason reason) {
        orders.values().forEach(ResidentCommands.Order::close);
        orders.clear();
        attacking = false;
        regrouping = false;
        endReason = reason;
        phase = Phase.FAILED_WAITING;
    }

    public void release() {
        owner.requireGroup(this);
        orders.values().forEach(ResidentCommands.Order::close);
        orders.clear();
        deployment = null;
        points = null;
        patrol = List.of();
        patrolAt = -1;
        assembledSince = Long.MIN_VALUE;
        mode = ResidentCommands.Mode.HOLD;
        adjustedColumn = false;
        phase = Phase.IDLE;
        commandId = null;
        commandKind = CommandKind.NONE;
        attacking = false;
        regrouping = false;
    }

    void startPoints(PointRoute route) {
        owner.requireGroup(this);
        List<Body> bodies = loaded();
        List<WorldPos> slots = route.slots(bodies);
        // The exact Deployment API retains its line endpoints. Click routes use their own centered slots.
        Deployment previous = deployment;
        deployment = new Deployment(route.captain(), route.captain(), route.front(), new Deployment.Formation(2, 1));
        ResidentCommands.Mode nextMode = route.attackMove ? ResidentCommands.Mode.GUARD : ResidentCommands.Mode.HOLD;
        try { issue(bodies, slots, Phase.LINE, nextMode); }
        catch (IllegalArgumentException | IllegalStateException rejected) { deployment = previous; throw rejected; }
        points = route;
        mode = nextMode;
        if (route.attackMove) { orders.values().forEach(ResidentCommands.Order::deliberateGuard); }
        authored(route.attackMove ? CommandKind.ATTACK_MOVE : CommandKind.DIRECT, route.commandId);
        patrol = List.of();
        patrolAt = -1;
    }

    boolean hasPointQueue() { return points != null && (points.appendable || points.points.size() == 1); }

    void appendPoint(List<Body> bodies, WorldPos point) {
        owner.requireGroup(this);
        if (!hasPointQueue() || points.loops() || phase == Phase.IDLE || phase == Phase.FAILED || phase == Phase.FAILED_WAITING
                || bodies.size() != members.size()
                || bodies.stream().anyMatch(body -> !members.contains(body.id())
                    || owner.commands.current(body.id()).filter(order -> order == orders.get(body.id())
                        && order.boundTo(body) && order.status() != ResidentCommands.Status.FAILED
                        && order.status() != ResidentCommands.Status.RELEASED).isEmpty())) {
            throw new IllegalArgumentException("point queue no longer owns the complete selection");
        }
        points.append(bodies, point);
    }

    void removeMember(UUID member) {
        members.remove(member);
        ResidentCommands.Order order = orders.remove(member);
        if (order != null) { order.close(); }
        changed = true;
    }

    void tick() {
        if ((!attacking && !regrouping && deployment == null) || phase == Phase.FAILED || phase == Phase.FAILED_WAITING) { return; }
        List<Body> bodies = loaded();
        for (Body body : List.copyOf(bodies)) {
            ResidentCommands.Order order = orders.get(body.id());
            if (order != null && owner.commands.current(body.id()).filter(current -> current != order).isPresent()) {
                // A direct individual order takes the member out of formation without touching its new handle.
                owner.remove(this, body.id());
            } else if (order != null && !order.boundTo(body)) {
                // Unload and reload can occur between group ticks. The old entity's released handle is not
                // an individual release of the newly loaded entity with the same persistent membership.
                changed = true;
            } else if (order != null && order.status() == ResidentCommands.Status.RELEASED && !attacking) {
                owner.remove(this, body.id());
            }
        }
        bodies = loaded();
        if (attacking) { tickAttack(bodies); return; }
        if (regrouping) { tickRegroup(bodies); return; }
        Set<UUID> present = new LinkedHashSet<>();
        bodies.forEach(body -> present.add(body.id()));
        if (points != null) {
            tickPoints(bodies, present);
            return;
        }
        if (changed || !present.equals(orders.keySet())) {
            changed = false;
            try { begin(deployment, true, mode); }
            catch (IllegalArgumentException unavailable) { fail(); }
            return;
        }
        if (orders.values().stream().anyMatch(order -> order.status() == ResidentCommands.Status.FAILED)) {
            if (phase == Phase.COLUMN && adjustedColumn) {
                // Local standing room is not proof that a height-adjusted transit slot is reachable.
                // Abandon that optional staging once; the exact final line still gets only one attempt.
                try {
                    List<WorldPos> line = deployment.line(bodies.size());
                    validate(bodies, line);
                    issue(bodies, line, Phase.LINE, mode);
                } catch (IllegalArgumentException unavailable) { fail(); }
            } else { fail(); }
        } else if (mode == ResidentCommands.Mode.GUARD && orders.values().stream().anyMatch(ResidentCommands.Order::engaged)) {
            assembledSince = Long.MIN_VALUE;
        } else if (phase == Phase.HOLDING && !assembled(bodies)) {
            phase = Phase.LINE;
            assembledSince = Long.MIN_VALUE;
        } else if (assembled(bodies)) {
            if (phase == Phase.COLUMN) {
                try {
                    if (++columnAt < bodies.size()) {
                        Body next = bodies.get(columnAt);
                        orders.get(next.id()).close();
                        orders.put(next.id(), order(next, column.get(columnAt), mode));
                        return;
                    }
                    List<WorldPos> line = deployment.line(bodies.size());
                    validate(bodies, line);
                    issue(bodies, line, Phase.LINE, mode);
                } catch (IllegalArgumentException unavailable) { fail(); }
            } else {
                phase = Phase.HOLDING;
                if (!patrol.isEmpty()) {
                    long now = owner.server.overworld().getGameTime();
                    if (assembledSince == Long.MIN_VALUE) { assembledSince = now; }
                    if (now - assembledSince >= PATROL_PAUSE) {
                        int next = (patrolAt + 1) % patrol.size();
                        try {
                            begin(patrol.get(next), true, ResidentCommands.Mode.GUARD);
                            patrolAt = next;
                        } catch (IllegalArgumentException unavailable) { fail(); }
                    }
                }
            }
        }
    }

    private void tickPoints(List<Body> bodies, Set<UUID> present) {
        if (bodies.isEmpty()) { points.waiting(); }
        if (orders.values().stream().anyMatch(ResidentCommands.Order::yieldingToDanger)) {
            points.waiting();
            pointsCombatPaused = true;
            assembledSince = Long.MIN_VALUE;
            return;
        }
        if (changed || !present.equals(orders.keySet())) {
            changed = false;
            if (bodies.isEmpty()) {
                orders.values().forEach(ResidentCommands.Order::close);
                orders.clear();
                phase = Phase.WAITING;
                assembledSince = Long.MIN_VALUE;
            } else {
                try { issue(bodies, points.slots(bodies), Phase.LINE, mode); }
                catch (IllegalArgumentException unavailable) { fail(); }
            }
            return;
        }
        if (bodies.isEmpty()) { return; }
        if (orders.values().stream().anyMatch(order -> order.status() == ResidentCommands.Status.FAILED)) {
            fail();
            return;
        }
        if (mode == ResidentCommands.Mode.GUARD && orders.values().stream().anyMatch(ResidentCommands.Order::engaged)) {
            points.waiting();
            pointsCombatPaused = true;
            assembledSince = Long.MIN_VALUE;
            return;
        }
        if (!assembled(bodies)) {
            phase = Phase.LINE;
            assembledSince = Long.MIN_VALUE;
            return;
        }
        if (pointsCombatPaused) {
            phase = points.atStop() ? Phase.HOLDING : Phase.REFORMING;
            long now = owner.server.overworld().getGameTime();
            if (assembledSince == Long.MIN_VALUE) { assembledSince = now; }
            if (now - assembledSince < PATROL_PAUSE) { return; }
            pointsCombatPaused = false;
        }
        try {
            if (points.atStop()) {
                phase = Phase.HOLDING;
                if (!points.loops() && !points.appendable) { return; }
                if (mode != ResidentCommands.Mode.GUARD) {
                    // Travel is deliberate player control. A fully assembled patrol can fight
                    // during its stop, then every survivor must reform before the dwell starts.
                    issue(bodies, points.slots(bodies), Phase.LINE, ResidentCommands.Mode.GUARD);
                    mode = ResidentCommands.Mode.GUARD;
                    return;
                }
                long now = owner.server.overworld().getGameTime();
                if (assembledSince == Long.MIN_VALUE) { assembledSince = now; }
                if (now - assembledSince < PATROL_PAUSE) { return; }
                if (!points.hasNext()) { return; }
                points.nextStop();
            }
            // Every advance waits for all available members. No member receives the distant final
            // waypoint while its squad is still at a previous captain position.
            WorldPos next = points.advance(bodies);
            List<WorldPos> slots = points.slots(bodies, next);
            mode = points.attackMove ? ResidentCommands.Mode.GUARD : ResidentCommands.Mode.HOLD;
            issue(bodies, slots, Phase.LINE, mode);
            points.advanced(next);
        } catch (IllegalArgumentException unavailable) { fail(); }
    }

    private boolean assembled(List<Body> bodies) {
        return !orders.isEmpty() && bodies.size() == orders.size() && bodies.stream().allMatch(body -> {
            ResidentCommands.Order order = orders.get(body.id());
            return order != null && order.boundTo(body) && order.status() == ResidentCommands.Status.HOLDING
                && Reach.standingIn(body.mob(), Stances.at(order.destination()));
        });
    }

    private void issue(List<Body> bodies, List<WorldPos> cells, Phase next, ResidentCommands.Mode mode) {
        List<ResidentCommands.Target> targets = new ArrayList<>();
        for (int n = 0; n < bodies.size(); n++) {
            Body body = bodies.get(n);
            WorldPos target = next == Phase.COLUMN && n > 0 ? WorldSpaces.at(body.mob()) : cells.get(n);
            targets.add(target(body, target, mode));
        }
        List<ResidentCommands.Order> replacements = owner.commands.replace(targets);
        if (points != null && mode == ResidentCommands.Mode.GUARD) {
            replacements.forEach(ResidentCommands.Order::deliberateGuard);
        }
        orders.values().forEach(ResidentCommands.Order::close);
        orders.clear();
        column = next == Phase.COLUMN ? List.copyOf(cells) : List.of();
        if (next != Phase.COLUMN) { adjustedColumn = false; }
        columnAt = 0;
        replacements.forEach(order -> orders.put(order.resident(), order));
        phase = next;
        changed = false;
        assembledSince = Long.MIN_VALUE;
    }

    private ResidentCommands.Order order(Body body, WorldPos destination, ResidentCommands.Mode mode) {
        return owner.commands.replace(List.of(target(body, destination, mode))).getFirst();
    }

    private ResidentCommands.Target target(Body body, WorldPos destination, ResidentCommands.Mode mode) {
        ServerLevel level = (ServerLevel) body.mob().level();
        var point = deployment.left();
        Vec3 from = WorldSpaces.world(level, point).orElseThrow();
        var offset = net.minecraft.core.BlockPos.containing(Vec3.atLowerCornerOf(point.cell()).add(deployment.front().scale(10)));
        Vec3 front = WorldSpaces.world(level, point.at(offset)).orElseThrow().subtract(from).normalize();
        return new ResidentCommands.Target(body, destination, mode,
            mode == ResidentCommands.Mode.RETREAT ? Optional.empty() : Optional.of(front));
    }

    private void validate(List<Body> bodies, List<WorldPos> cells) {
        for (int n = 0; n < bodies.size(); n++) {
            Body body = bodies.get(n);
            WorldPos cell = cells.get(n);
            owner.commands.validate(body, cell);
            if (!Footing.withFeetAt(body.mob().level(), cell.block(body.mob().level()))) {
                throw new IllegalArgumentException("a formation slot has no standing room");
            }
        }
    }

    void validateSelection(List<Body> bodies, List<Deployment> route) {
        for (Deployment stop : route) {
            List<WorldPos> cells = stop.line(bodies.size());
            validate(bodies, cells);
            for (int n = 0; n < bodies.size(); n++) {
                Body body = bodies.get(n);
                var level = body.mob().level();
                var feet = cells.get(n).block(level);
                if (!level.hasChunksAt(feet.offset(-1, -1, -1), feet.offset(1, 2, 1))
                        || !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()
                        || WalkNodeEvaluator.getPathTypeStatic(new PathfindingContext(level, body.mob()), feet.mutable())
                            != PathType.WALKABLE) {
                    throw new IllegalArgumentException("a selection formation slot is unsafe");
                }
            }
        }
    }

    /** Transit slots may follow at most two blocks of local rise/drop; the final line remains exact. */
    private List<WorldPos> terrainColumn(List<Body> bodies, List<WorldPos> planned) {
        List<WorldPos> cells = new ArrayList<>(bodies.size());
        for (int n = 0; n < bodies.size(); n++) {
            Body body = bodies.get(n);
            ServerLevel level = (ServerLevel) body.mob().level();
            WorldPos desired = planned.get(n);
            WorldPos found = null;
            for (int dy : new int[] {0, -1, 1, -2, 2}) {
                WorldPos candidate = desired.at(desired.cell().offset(0, dy, 0));
                if (!candidate.sameRealm(WorldSpaces.at(body.mob())) || !candidate.in(level)) { continue; }
                var feet = candidate.block(level);
                if (!level.hasChunksAt(feet.offset(-1, -1, -1), feet.offset(1, 2, 1))
                        || !Footing.withFeetAt(level, feet)
                        || !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()
                        || WalkNodeEvaluator.getPathTypeStatic(new PathfindingContext(level, body.mob()), feet.mutable())
                            != PathType.WALKABLE || cells.contains(candidate)) { continue; }
                owner.commands.validate(body, candidate);
                found = candidate;
                break;
            }
            if (found == null) { throw new IllegalArgumentException("a transit column slot has no safe local footing"); }
            cells.add(found);
        }
        return List.copyOf(cells);
    }

    private List<Body> loaded() { return loaded(deployment); }
    private List<Body> loaded(Deployment intent) {
        return members.stream().map(owner::loaded).flatMap(Optional::stream)
            .filter(body -> intent == null || intent.left().sameRealm(WorldSpaces.at(body.mob()))).toList();
    }
    private void fail() {
        if (points != null || commandKind == CommandKind.ATTACK || commandKind == CommandKind.STOP) {
            failedWaiting(EndReason.FAILED);
            return;
        }
        orders.values().forEach(ResidentCommands.Order::close);
        orders.clear();
        phase = Phase.FAILED;
    }
}
