package io.github.izakyl.folkways.plugins.combat.group;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.server.MinecraftServer;

/** Colony-owned actual membership; player shortcut selections belong to a future War client. */
public final class UnitGroups {
    final Colony colony;
    final MinecraftServer server;
    final ResidentCommands commands;
    private final Map<UUID, UnitGroup> groups = new LinkedHashMap<>();
    private final Map<UUID, UnitGroup> membership = new LinkedHashMap<>();

    UnitGroups(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
        commands = ResidentCommands.of(colony);
        Set<UUID> roster = roster();
        for (Reader saved : Reader.of(colony.kept(GroupContent.ID)).children("groups")) {
            saved.uuid("id").filter(id -> !groups.containsKey(id)).ifPresent(id -> {
                UnitGroup group = new UnitGroup(this, id);
                saved.uuids("members").stream().filter(roster::contains).filter(member -> !membership.containsKey(member))
                    .forEach(member -> { group.members.add(member); membership.put(member, group); });
                if (!group.members.isEmpty()) { groups.put(id, group); }
            });
        }
    }

    public static UnitGroups of(Colony colony) {
        return colony.service(GroupContent.ID, UnitGroups.class).orElseThrow();
    }

    public UnitGroup create(Collection<? extends Body> residents) {
        requireServerThread();
        List<? extends Body> distinct = residents.stream().distinct().toList();
        if (distinct.isEmpty()) { throw new IllegalArgumentException("groups require members"); }
        distinct.forEach(this::validate);
        UnitGroup group = new UnitGroup(this, UUID.randomUUID());
        groups.put(group.id(), group);
        distinct.forEach(body -> add(group, body));
        return group;
    }

    public Optional<UnitGroup> group(UUID id) { requireServerThread(); return Optional.ofNullable(groups.get(id)); }
    public Optional<UnitGroup> ofResident(UUID resident) { requireServerThread(); return Optional.ofNullable(membership.get(resident)); }

    /** A client selection becomes actual membership only after its complete command is accepted. */
    public UnitGroup commandSelection(Collection<? extends Body> residents, List<Deployment> route, boolean patrol) {
        requireServerThread();
        List<Body> people = residents.stream().map(body -> (Body) body).distinct().toList();
        if (people.isEmpty() || route.isEmpty() || (!patrol && route.size() != 1)
                || (patrol && (route.size() < 2 || route.size() > 16))) {
            throw new IllegalArgumentException("invalid selection route");
        }
        people.forEach(this::validate);
        UnitGroup group = new UnitGroup(this, UUID.randomUUID());
        people.forEach(body -> group.members.add(body.id()));
        group.validateSelection(people, route);
        groups.put(group.id(), group);
        try {
            if (patrol) { group.patrol(route); }
            else { group.move(route.getFirst()); }
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            groups.remove(group.id(), group);
            throw rejected;
        }
        // New handles already own only the selected residents. Closing an old handle cannot
        // cancel its replacement, and the old group's unselected members keep their handles.
        for (Body body : people) {
            UnitGroup previous = membership.put(body.id(), group);
            if (previous != null) {
                previous.removeMember(body.id());
                if (previous.members.isEmpty()) { groups.remove(previous.id(), previous); }
            }
        }
        save();
        return group;
    }

    /** Point intent: the click is the logical captain, with an optional prefix followed by a cycle. */
    public boolean commandSelectionPoints(List<Body> residents, List<WorldPos> points, int cycleStart) {
        requireServerThread();
        List<Body> people = residents.stream().distinct().toList();
        people.forEach(this::validate);
        PointRoute route = new PointRoute(this, people, points, cycleStart);
        return startSelectionPoints(people, route);
    }

    /** Each Ctrl click is committed immediately; only the exact actual squad can extend its queue. */
    public boolean appendSelectionPoint(List<Body> residents, WorldPos point) {
        requireServerThread();
        List<Body> people = residents.stream().distinct().toList();
        if (people.isEmpty()) { throw new IllegalArgumentException("point queues require members"); }
        people.forEach(this::validate);
        UnitGroup existing = membership.get(people.getFirst().id());
        Set<UUID> selected = people.stream().map(Body::id).collect(Collectors.toSet());
        if (existing != null && existing.hasPointQueue() && existing.members.equals(selected)
                && people.stream().allMatch(body -> membership.get(body.id()) == existing)) {
            existing.appendPoint(people, point);
            return true;
        }
        // A changed selection or legacy formation begins a fresh queue. All
        // footing and member validation precedes installing or releasing any command handle.
        return startSelectionPoints(people, new PointRoute(this, people, List.of(point), -1, true));
    }

    private boolean startSelectionPoints(List<Body> people, PointRoute route) {
        UnitGroup group = new UnitGroup(this, UUID.randomUUID());
        people.forEach(body -> group.members.add(body.id()));
        groups.put(group.id(), group);
        try { group.startPoints(route); }
        catch (IllegalArgumentException | IllegalStateException rejected) {
            groups.remove(group.id(), group);
            throw rejected;
        }
        for (Body body : people) {
            UnitGroup previous = membership.put(body.id(), group);
            if (previous != null) {
                previous.removeMember(body.id());
                if (previous.members.isEmpty()) { groups.remove(previous.id(), previous); }
            }
        }
        save();
        return true;
    }

    /** Detach only selected members before an explicit individual order takes over. */
    public void releaseSelection(List<Body> residents) {
        requireServerThread();
        List<Body> people = residents.stream().distinct().toList();
        people.forEach(this::validate);
        for (Body body : people) {
            UnitGroup previous = membership.remove(body.id());
            if (previous != null) {
                previous.removeMember(body.id());
                if (previous.members.isEmpty()) { groups.remove(previous.id(), previous); }
            }
        }
        save();
    }

    void add(UnitGroup group, Body body) {
        requireGroup(group);
        validate(body);
        UnitGroup previous = membership.get(body.id());
        if (previous == group) { return; }
        if (previous != null) { remove(previous, body.id()); }
        group.members.add(body.id());
        membership.put(body.id(), group);
        group.changed = true;
        save();
    }

    void remove(UnitGroup group, UUID resident) {
        requireGroup(group);
        group.removeMember(resident);
        membership.remove(resident, group);
        save();
    }

    public void dissolve(UnitGroup group) {
        requireGroup(group);
        group.release();
        group.members.forEach(member -> membership.remove(member, group));
        group.members.clear();
        groups.remove(group.id(), group);
        save();
    }

    Optional<Body> loaded(UUID resident) {
        for (var level : server.getAllLevels()) {
            var entity = level.getEntity(resident);
            if (entity != null) {
                return Bodies.of(entity).filter(body -> body.mob().isAlive() && !body.mob().isRemoved()
                    && body.colonyId().filter(colony.id()::equals).isPresent());
            }
        }
        return Optional.empty();
    }

    void tick(MinecraftServer ignored) {
        Set<UUID> roster = roster();
        for (UnitGroup group : List.copyOf(groups.values())) {
            for (UUID member : List.copyOf(group.members)) {
                if (!roster.contains(member)) { remove(group, member); }
            }
            group.tick();
        }
    }

    void clear() { groups.values().forEach(UnitGroup::release); groups.clear(); membership.clear(); }

    void save() {
        colony.keep(GroupContent.ID, Writer.of().children("groups", groups.values(), group ->
            Writer.of().uuid("id", group.id()).uuids("members", group.members).tag()).tag());
    }

    void requireGroup(UnitGroup group) {
        requireServerThread();
        if (groups.get(group.id()) != group) { throw new IllegalStateException("group is no longer active in this colony"); }
    }

    private Set<UUID> roster() {
        return colony.residents().stream().map(resident -> resident.id()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void validate(Body body) {
        if (!body.kind().id().equals(PersonBody.ID) || loaded(body.id()).filter(found -> found.mob() == body.mob()).isEmpty()
                || colony.holdingOf(body.id()).isEmpty()) {
            throw new IllegalArgumentException("groups require loaded people held by this colony");
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) { throw new IllegalStateException("unit groups belong to the server thread"); }
    }
}
