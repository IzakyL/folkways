package io.github.izakyl.folkways.plugins.combat.control;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroup;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroups;
import io.github.izakyl.folkways.plugins.combat.net.CombatPackets;
import io.github.izakyl.folkways.plugins.combat.net.SelectionOrders;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Transient, server-authoritative selection and shortcut ownership. All access belongs to the server thread. */
public final class CombatSelectionOwnership {
    public static final int GROUPS = 9;
    public static final long LEASE_TICKS = 100;

    public record Snapshot(long revision, List<UUID> selection, List<List<UUID>> groups) {
        public Snapshot {
            selection = List.copyOf(selection);
            groups = groups.stream().map(List::copyOf).toList();
        }
    }

    private interface TrackedControl { void release(UUID resident); }

    private final class GroupControl implements TrackedControl {
        private final UnitGroup group;
        private final UUID command;

        GroupControl(UnitGroup group, UUID command) { this.group = group; this.command = command; }

        public void release(UUID resident) {
            // A newer group, a new point route on this group, or a book-page deployment owns its own controls.
            if (UnitGroups.of(colony).ofResident(resident).filter(current -> current == group).isPresent()
                    && group.commandId().filter(command::equals).isPresent()) {
                // remove closes only this group's actual handle, whose identity cannot cancel an individual replacement.
                group.remove(resident);
                if (group.members().isEmpty()) { UnitGroups.of(colony).dissolve(group); }
            }
        }
    }

    private record AttackControl(ResidentCommands.Order order) implements TrackedControl {
        public void release(UUID ignored) { order.close(); }
    }

    private static final class Context {
        final ServerPlayer player;
        final ServerLevel level;
        final Realm realm;
        final LinkedHashSet<UUID> selection = new LinkedHashSet<>();
        final List<LinkedHashSet<UUID>> groups = new ArrayList<>();
        final Map<UUID, Body> bodies = new LinkedHashMap<>();
        final Map<UUID, TrackedControl> controls = new LinkedHashMap<>();
        long until;

        Context(ServerPlayer player) {
            this.player = player;
            level = player.serverLevel();
            realm = WorldSpaces.at(player).realm();
            for (int i = 0; i < GROUPS; i++) { groups.add(new LinkedHashSet<>()); }
        }

        Set<UUID> members() {
            Set<UUID> all = new LinkedHashSet<>(selection);
            groups.forEach(all::addAll);
            return all;
        }
    }

    private final Colony colony;
    private final MinecraftServer server;
    private final Map<UUID, Context> contexts = new HashMap<>();
    private final Map<UUID, UUID> owners = new HashMap<>();
    // Retain revision tombstones after release so delayed acknowledgements cannot restore an old selection.
    private final Map<UUID, Long> revisions = new HashMap<>();
    private long prunedAt = Long.MIN_VALUE;
    private boolean pruning;

    CombatSelectionOwnership(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
    }

    public static CombatSelectionOwnership of(Colony colony) {
        return colony.service(SelectionControlContent.ID, CombatSelectionOwnership.class).orElseThrow();
    }

    /** Partially accepts available members; a forged, unloaded or already-owned UUID never becomes owned. */
    public boolean select(ServerPlayer player, List<UUID> members) {
        requireThread();
        if (members.size() > CombatPackets.MAX_MEMBERS || !authorized(player)) { return false; }
        Context context = context(player);
        Set<UUID> accepted = new LinkedHashSet<>();
        for (UUID id : members) {
            body(player, id).filter(candidate -> claimable(player, candidate)).ifPresent(candidate -> {
                accepted.add(id);
                context.bodies.put(id, candidate);
            });
        }
        if (!context.selection.equals(accepted)) {
            context.selection.clear();
            context.selection.addAll(accepted);
            changed(player.getUUID());
        }
        reconcile(context);
        renew(context);
        return true;
    }

    /** Exact-set toggle: repeated binding clears just that shortcut, preserving the visible selection. */
    public boolean bindGroup(ServerPlayer player, int slot) {
        requireThread();
        if (slot < 0 || slot >= GROUPS || !authorized(player)) { return false; }
        Context context = context(player);
        Set<UUID> group = context.groups.get(slot);
        Set<UUID> next = group.equals(context.selection) ? Set.of() : Set.copyOf(context.selection);
        if (!group.equals(next)) {
            group.clear();
            group.addAll(next);
            changed(player.getUUID());
        }
        reconcile(context);
        renew(context);
        return true;
    }

    public boolean recallGroup(ServerPlayer player, int slot) {
        requireThread();
        if (slot < 0 || slot >= GROUPS || !authorized(player)) { return false; }
        Context context = context(player);
        Set<UUID> group = context.groups.get(slot);
        if (!context.selection.equals(group)) {
            context.selection.clear();
            context.selection.addAll(group);
            changed(player.getUUID());
        }
        reconcile(context);
        renew(context);
        return true;
    }

    /** Renew only the existing authoritative state; clients cannot supply a union through a heartbeat. */
    public boolean heartbeat(ServerPlayer player) {
        requireThread();
        prune();
        Context context = contexts.get(player.getUUID());
        if (context == null || !valid(context)) { return false; }
        renew(context);
        return true;
    }

    public void clearContext(ServerPlayer player) {
        requireThread();
        clear(player.getUUID());
    }

    /** Atomically forget the selected residents from every reference before releasing their tracked controls. */
    public boolean releaseSelected(ServerPlayer player, List<UUID> residents) {
        requireThread();
        prune();
        Context context = contexts.get(player.getUUID());
        Set<UUID> selected = new LinkedHashSet<>(residents);
        if (context == null || !valid(context) || selected.isEmpty()
                || !context.selection.containsAll(selected)
                || selected.stream().anyMatch(id -> !player.getUUID().equals(owners.get(id)))) { return false; }
        context.selection.removeAll(selected);
        context.groups.forEach(group -> group.removeAll(selected));
        changed(player.getUUID());
        reconcile(context);
        renew(context);
        return true;
    }

    public Snapshot snapshot(ServerPlayer player) {
        requireThread();
        prune();
        Context context = contexts.get(player.getUUID());
        return new Snapshot(revisions.getOrDefault(player.getUUID(), 0L),
            context == null ? List.of() : List.copyOf(context.selection),
            context == null ? java.util.Collections.nCopies(GROUPS, List.of())
                : context.groups.stream().map(group -> List.copyOf(group)).toList());
    }

    public boolean owns(ServerPlayer player, Body body) {
        requireThread();
        prune();
        Context context = contexts.get(player.getUUID());
        return context != null && valid(context) && player.getUUID().equals(owners.get(body.id()))
            && context.bodies.get(body.id()) == body
            && body(player, body.id()).filter(found -> found == body).isPresent();
    }

    public boolean selected(ServerPlayer player, Body body) {
        return owns(player, body) && contexts.get(player.getUUID()).selection.contains(body.id());
    }

    public boolean canSelect(ServerPlayer player, Body candidate) {
        requireThread();
        prune();
        return authorized(player) && body(player, candidate.id()).filter(found -> found == candidate).isPresent()
            && claimable(player, candidate);
    }

    /** Ownership check for persisted groups, including members that are currently unloaded. */
    public boolean available(ServerPlayer player, UUID resident) {
        requireThread();
        prune();
        UUID owner = owners.get(resident);
        return authorized(player) && (owner == null || owner.equals(player.getUUID()));
    }

    /** A page's existing colony authority can inspect ownership without holding a book or creating a lease. */
    public boolean availableOnPage(ServerPlayer player, UUID resident) {
        requireThread();
        prune();
        UUID owner = owners.get(resident);
        return player.getServer() == server && player.isAlive() && !player.isRemoved() && !player.isSpectator()
            && Pages.colonyOf(player).filter(found -> found.id().equals(colony.id())).isPresent()
            && (owner == null || owner.equals(player.getUUID()));
    }

    /** Record only a newly accepted RTS command generation, never a pre-existing autonomous or page command. */
    public void trackGroup(ServerPlayer player, UnitGroup group) {
        requireThread();
        prune();
        Context context = contexts.get(player.getUUID());
        UUID command = group.commandId().orElseThrow(() -> new IllegalArgumentException("not an active group command"));
        if (context == null || !valid(context) || group.members().isEmpty()
                || group.members().stream().anyMatch(id -> !player.getUUID().equals(owners.get(id))
                    || UnitGroups.of(colony).ofResident(id).filter(current -> current == group).isEmpty())) {
            throw new IllegalArgumentException("RTS group is not controlled by this session");
        }
        TrackedControl control = new GroupControl(group, command);
        group.members().forEach(id -> context.controls.put(id, control));
    }

    /** Track the exact accepted attack handles; closing an old handle cannot release a later replacement. */
    public void trackAttack(ServerPlayer player, List<ResidentCommands.Order> orders) {
        requireThread();
        prune();
        Context context = contexts.get(player.getUUID());
        if (context == null || !valid(context) || orders.isEmpty()
                || orders.stream().anyMatch(order -> order.mode() != ResidentCommands.Mode.ATTACK
                    || !player.getUUID().equals(owners.get(order.resident()))
                    || ResidentCommands.of(colony).current(order.resident()).filter(current -> current == order).isEmpty())) {
            throw new IllegalArgumentException("RTS attacks are not controlled by this session");
        }
        orders.forEach(order -> context.controls.put(order.resident(), new AttackControl(order)));
    }

    private boolean claimable(ServerPlayer player, Body body) {
        UUID owner = owners.get(body.id());
        return owner == null || owner.equals(player.getUUID());
    }

    private Context context(ServerPlayer player) {
        // Mutating selection/shortcut operations recheck current owners even after this tick's cached scan.
        // A keeper who just lost authority cannot block a new claimant until the next simulation tick.
        prune(true);
        // A player cannot keep controls alive in a previous colony by switching their current book.
        for (Colony other : Colonies.all(server)) {
            if (!other.id().equals(colony.id())) {
                other.service(SelectionControlContent.ID, CombatSelectionOwnership.class)
                    .ifPresent(service -> service.clear(player.getUUID()));
            }
        }
        return contexts.computeIfAbsent(player.getUUID(), ignored -> new Context(player));
    }

    private boolean authorized(ServerPlayer player) {
        return player.getServer() == server && !player.isRemoved()
            && SelectionOrders.authorized(player, colony.id()).isPresent();
    }

    private Optional<Body> body(ServerPlayer player, UUID id) {
        return Optional.ofNullable(player.serverLevel().getEntity(id)).flatMap(Bodies::of)
            .filter(body -> body.kind().id().equals(PersonBody.ID) && body.mob().isAlive() && !body.mob().isRemoved()
                && !body.mob().isPassenger() && colony.holdingOf(id).isPresent()
                && body.colonyId().filter(colony.id()::equals).isPresent()
                && WorldSpaces.at(body.mob()).sameRealm(WorldSpaces.at(player)));
    }

    private void renew(Context context) { context.until = now() + LEASE_TICKS; }
    private long now() { return server.overworld().getGameTime(); }
    private void changed(UUID player) { revisions.merge(player, 1L, Long::sum); }

    private void reconcile(Context context) {
        UUID player = context.player.getUUID();
        Set<UUID> members = context.members();
        // Publish the exact union before releasing controls: release callbacks may query this service again.
        owners.entrySet().removeIf(entry -> entry.getValue().equals(player) && !members.contains(entry.getKey()));
        context.bodies.keySet().retainAll(members);
        members.forEach(id -> owners.put(id, player));
        for (UUID id : List.copyOf(context.controls.keySet())) {
            if (!members.contains(id)) { context.controls.remove(id).release(id); }
        }
    }

    private void clear(UUID player) {
        Context context = contexts.remove(player);
        owners.entrySet().removeIf(entry -> entry.getValue().equals(player));
        if (context != null) {
            changed(player);
            context.controls.forEach((resident, control) -> control.release(resident));
        }
    }

    void removed(UUID resident) {
        requireThread();
        UUID player = owners.remove(resident);
        Context context = contexts.get(player);
        if (context == null) { return; }
        TrackedControl control = context.controls.remove(resident);
        context.selection.remove(resident);
        context.groups.forEach(group -> group.remove(resident));
        context.bodies.remove(resident);
        changed(player);
        if (control != null) { control.release(resident); }
    }

    private boolean valid(Context context) {
        ServerPlayer player = context.player;
        return context.until > now() && authorized(player) && player.serverLevel() == context.level
            && WorldSpaces.at(player).realm().equals(context.realm);
    }

    private void prune() { prune(false); }

    private void prune(boolean fresh) {
        long tick = now();
        // Runner/kit/roster reads share one scan per game tick. Mutators maintain the maps synchronously,
        // and explicit selection mutations force a new scan before acquiring anybody else's old ownership.
        // Tracked-control cleanup can call group callbacks which read us again; never recurse into that scan.
        if (pruning || !fresh && prunedAt == tick) { return; }
        pruning = true;
        try {
            for (Context context : List.copyOf(contexts.values())) {
                ServerPlayer player = context.player;
                if (contexts.get(player.getUUID()) != context) { continue; }
                if (!valid(context)) {
                    clear(player.getUUID());
                    continue;
                }
                for (UUID id : List.copyOf(context.members())) {
                    if (body(player, id).filter(found -> found == context.bodies.get(id)).isEmpty()) { removed(id); }
                }
            }
            prunedAt = tick;
        } finally {
            pruning = false;
        }
    }

    List<Urge> urges(Worker who) {
        prune();
        return owners.containsKey(who.resident().id()) ? List.of(Urge.reserve(SelectionControlContent.ID)) : List.of();
    }

    boolean reserved(Body body) {
        requireThread();
        prune();
        return owners.containsKey(body.id());
    }

    void tick(MinecraftServer ignored) { prune(); }
    void close() { contexts.clear(); owners.clear(); revisions.clear(); prunedAt = Long.MIN_VALUE; }
    private void requireThread() {
        if (!server.isSameThread()) { throw new IllegalStateException("selection ownership belongs to the server thread"); }
    }
}
