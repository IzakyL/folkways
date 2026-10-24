package io.github.izakyl.folkways.plugins.combat.group;

import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public final class UnitGroup {
    public enum Phase { IDLE, COLUMN, LINE, HOLDING, ENGAGING, WAITING, FAILED }

    private static final int PATROL_PAUSE = 20;

    private final UnitGroups owner;
    private final UUID id;
    final Set<UUID> members = new LinkedHashSet<>();
    private final Map<UUID, ResidentCommands.Order> orders = new LinkedHashMap<>();
    private Deployment deployment;
    private Phase phase = Phase.IDLE;
    private List<WorldPos> column = List.of();
    private int columnAt;
    private List<Deployment> patrol = List.of();
    private int patrolAt = -1;
    private long assembledSince = Long.MIN_VALUE;
    boolean changed;

    UnitGroup(UnitGroups owner, UUID id) { this.owner = owner; this.id = id; }

    public UUID id() { return id; }
    public Set<UUID> members() { return Set.copyOf(members); }
    public Phase phase() {
        return !patrol.isEmpty() && orders.values().stream().anyMatch(ResidentCommands.Order::engaged)
            ? Phase.ENGAGING : phase;
    }
    /** Transient route and current stop; membership alone is persisted. */
    public List<Deployment> patrolRoute() { return patrol; }
    public int patrolStop() { return patrolAt; }
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
        begin(next.getFirst(), true, true);
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
        begin(intent, column, false);
        patrol = List.of();
        patrolAt = -1;
    }

    private void begin(Deployment intent, boolean column, boolean guarding) {
        owner.requireGroup(this);
        List<Body> bodies = loaded(intent);
        if (bodies.isEmpty()) {
            orders.values().forEach(ResidentCommands.Order::close);
            orders.clear();
            deployment = intent;
            phase = Phase.WAITING;
            assembledSince = Long.MIN_VALUE;
            return;
        }
        List<WorldPos> line = intent.line(bodies.size());
        validate(bodies, line);
        List<WorldPos> cells = column ? intent.column(bodies.size()) : line;
        if (column) {
            try { validate(bodies, cells); }
            catch (IllegalArgumentException unavailable) { cells = line; column = false; }
        }
        deployment = intent;
        issue(bodies, cells, column ? Phase.COLUMN : Phase.LINE, guarding);
    }

    public void release() {
        owner.requireGroup(this);
        orders.values().forEach(ResidentCommands.Order::close);
        orders.clear();
        deployment = null;
        patrol = List.of();
        patrolAt = -1;
        assembledSince = Long.MIN_VALUE;
        phase = Phase.IDLE;
    }

    void removeMember(UUID member) {
        members.remove(member);
        ResidentCommands.Order order = orders.remove(member);
        if (order != null) { order.close(); }
        changed = true;
    }

    void tick() {
        if (deployment == null || phase == Phase.FAILED) { return; }
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
            } else if (order != null && order.status() == ResidentCommands.Status.RELEASED) {
                owner.remove(this, body.id());
            }
        }
        bodies = loaded();
        Set<UUID> present = new LinkedHashSet<>();
        bodies.forEach(body -> present.add(body.id()));
        if (changed || !present.equals(orders.keySet())) {
            changed = false;
            try { begin(deployment, true, !patrol.isEmpty()); }
            catch (IllegalArgumentException unavailable) { fail(); }
            return;
        }
        if (orders.values().stream().anyMatch(order -> order.status() == ResidentCommands.Status.FAILED)) {
            fail();
        } else if (!patrol.isEmpty() && orders.values().stream().anyMatch(ResidentCommands.Order::engaged)) {
            assembledSince = Long.MIN_VALUE;
        } else if (phase == Phase.HOLDING && orders.values().stream()
                .anyMatch(order -> order.status() == ResidentCommands.Status.MOVING)) {
            phase = Phase.LINE;
            assembledSince = Long.MIN_VALUE;
        } else if (!orders.isEmpty() && orders.values().stream()
                .allMatch(order -> order.status() == ResidentCommands.Status.HOLDING)) {
            if (phase == Phase.COLUMN) {
                try {
                    if (++columnAt < bodies.size()) {
                        Body next = bodies.get(columnAt);
                        orders.get(next.id()).close();
                        orders.put(next.id(), order(next, column.get(columnAt), !patrol.isEmpty()));
                        return;
                    }
                    List<WorldPos> line = deployment.line(bodies.size());
                    validate(bodies, line);
                    issue(bodies, line, Phase.LINE, !patrol.isEmpty());
                } catch (IllegalArgumentException unavailable) { fail(); }
            } else {
                phase = Phase.HOLDING;
                if (!patrol.isEmpty()) {
                    long now = owner.server.overworld().getGameTime();
                    if (assembledSince == Long.MIN_VALUE) { assembledSince = now; }
                    if (now - assembledSince >= PATROL_PAUSE) {
                        int next = (patrolAt + 1) % patrol.size();
                        try {
                            begin(patrol.get(next), true, true);
                            patrolAt = next;
                        } catch (IllegalArgumentException unavailable) { fail(); }
                    }
                }
            }
        }
    }

    private void issue(List<Body> bodies, List<WorldPos> cells, Phase next, boolean guarding) {
        orders.values().forEach(ResidentCommands.Order::close);
        orders.clear();
        column = next == Phase.COLUMN ? List.copyOf(cells) : List.of();
        columnAt = 0;
        for (int n = 0; n < bodies.size(); n++) {
            Body body = bodies.get(n);
            // Followers hold clear of the approach until their predecessor reaches its column slot.
            WorldPos target = next == Phase.COLUMN && n > 0 ? WorldSpaces.at(body.mob()) : cells.get(n);
            orders.put(body.id(), order(body, target, guarding));
        }
        phase = next;
        changed = false;
        assembledSince = Long.MIN_VALUE;
    }

    private ResidentCommands.Order order(Body body, WorldPos destination, boolean guarding) {
        ServerLevel level = (ServerLevel) body.mob().level();
        var point = deployment.left();
        Vec3 from = WorldSpaces.world(level, point).orElseThrow();
        var offset = net.minecraft.core.BlockPos.containing(Vec3.atLowerCornerOf(point.cell()).add(deployment.front().scale(10)));
        Vec3 front = WorldSpaces.world(level, point.at(offset)).orElseThrow().subtract(from).normalize();
        return guarding ? owner.commands.guard(body, destination, front) : owner.commands.hold(body, destination, front);
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

    private List<Body> loaded() { return loaded(deployment); }
    private List<Body> loaded(Deployment intent) {
        return members.stream().map(owner::loaded).flatMap(Optional::stream)
            .filter(body -> intent == null || intent.left().sameRealm(WorldSpaces.at(body.mob()))).toList();
    }
    private void fail() {
        orders.values().forEach(ResidentCommands.Order::close);
        orders.clear();
        phase = Phase.FAILED;
    }
}
