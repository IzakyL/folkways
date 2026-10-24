package io.github.izakyl.folkways.plugins.combat.group;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
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
                    .limit(8).forEach(member -> { group.members.add(member); membership.put(member, group); });
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
        if (distinct.isEmpty() || distinct.size() > 8) { throw new IllegalArgumentException("groups have one to eight members"); }
        distinct.forEach(this::validate);
        UnitGroup group = new UnitGroup(this, UUID.randomUUID());
        groups.put(group.id(), group);
        distinct.forEach(body -> add(group, body));
        return group;
    }

    public Optional<UnitGroup> group(UUID id) { requireServerThread(); return Optional.ofNullable(groups.get(id)); }
    public Optional<UnitGroup> ofResident(UUID resident) { requireServerThread(); return Optional.ofNullable(membership.get(resident)); }

    void add(UnitGroup group, Body body) {
        requireGroup(group);
        validate(body);
        if (!group.members.contains(body.id()) && group.members.size() >= 8) {
            throw new IllegalArgumentException("a group already has eight members");
        }
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
