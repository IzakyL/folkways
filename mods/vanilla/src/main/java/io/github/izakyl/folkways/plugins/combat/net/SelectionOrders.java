package io.github.izakyl.folkways.plugins.combat.net;

import io.github.izakyl.folkways.core.api.colony.Books;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.plugins.combat.Threats;
import io.github.izakyl.folkways.plugins.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroup;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroups;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

/** Authoritative selection commands; clients submit intent, never navigation or membership edits. */
public final class SelectionOrders {
    private SelectionOrders() { }

    public static Optional<Colony> authorized(ServerPlayer player, UUID expected) {
        if (!player.getServer().isSameThread() || player.isSpectator() || !player.isAlive()) { return Optional.empty(); }
        return Pages.colonyOf(player).filter(colony -> colony.id().equals(expected))
            .filter(colony -> Books.ofHeldBook(player, expected).filter(held -> held.id().equals(expected)).isPresent());
    }

    public static boolean submit(ServerPlayer player, UUID colonyId, List<UUID> residents,
            List<WorldPos> points, boolean patrol) {
        return submit(player, colonyId, residents, points, patrol ? 0 : -1);
    }

    public static boolean validCycle(int count, int cycleStart) {
        return cycleStart == -1 ? count == 1 : cycleStart >= 0 && cycleStart < count - 1;
    }

    public static boolean submit(ServerPlayer player, UUID colonyId, List<UUID> residents,
            List<WorldPos> points, int cycleStart) {
        return submit(player, colonyId, residents, points, cycleStart, false);
    }

    public static boolean submit(ServerPlayer player, UUID colonyId, List<UUID> residents,
            List<WorldPos> points, int cycleStart, boolean attackMove) {
        try {
            Colony colony = authorized(player, colonyId).orElseThrow(() -> new IllegalArgumentException("unauthorized"));
            if (residents.isEmpty() || residents.size() > CombatPackets.MAX_MEMBERS
                    || points.isEmpty() || points.size() > CombatPackets.MAX_POINTS
                    || !validCycle(points.size(), cycleStart)) {
                return false;
            }
            // Duplicate identifiers are harmless; every distinct selected person is retained.
            List<Body> people = new LinkedHashSet<>(residents).stream()
                .map(id -> selectedBody(player, colony, id)).toList();
            points.forEach(point -> validPoint(player, point));
            boolean accepted = UnitGroups.of(colony).commandSelectionPoints(people, points, cycleStart, attackMove);
            if (accepted) { trackGroup(player, colony, people); }
            return accepted;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return false;
        }
    }

    /** Each click commits one waypoint without restarting any already accepted part of the route. */
    public static boolean append(ServerPlayer player, UUID colonyId, List<UUID> residents, WorldPos point) {
        return append(player, colonyId, residents, point, false);
    }

    public static boolean append(ServerPlayer player, UUID colonyId, List<UUID> residents, WorldPos point, boolean attackMove) {
        try {
            Colony colony = authorized(player, colonyId).orElseThrow(() -> new IllegalArgumentException("unauthorized"));
            if (residents.isEmpty() || residents.size() > CombatPackets.MAX_MEMBERS) { return false; }
            List<Body> people = new LinkedHashSet<>(residents).stream().map(id -> selectedBody(player, colony, id)).toList();
            validPoint(player, point);
            boolean accepted = UnitGroups.of(colony).appendSelectionPoint(people, point, attackMove);
            if (accepted) { trackGroup(player, colony, people); }
            return accepted;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return false;
        }
    }

    /** A reply can only expose the exact squad that is still selected by this player. */
    static Optional<UnitGroup> route(ServerPlayer player, UUID colonyId, List<UUID> residents) {
        if (residents.isEmpty() || residents.size() > CombatPackets.MAX_MEMBERS) { return Optional.empty(); }
        return authorized(player, colonyId).flatMap(colony -> {
            Set<UUID> members = new LinkedHashSet<>(residents);
            if (members.stream().anyMatch(id -> body(player, colony, id)
                    .filter(person -> CombatSelectionOwnership.of(colony).selected(player, person)).isEmpty())) {
                return Optional.empty();
            }
            return UnitGroups.of(colony).ofResident(residents.getFirst())
                .filter(group -> group.members().equals(members));
        });
    }

    /** Players are deliberately outside this command's PvP scope; only a loaded nonfriendly Mob is accepted. */
    public static boolean attack(ServerPlayer player, UUID colonyId, List<UUID> residents, UUID targetId) {
        try {
            Colony colony = authorized(player, colonyId).orElseThrow(() -> new IllegalArgumentException("unauthorized"));
            if (residents.isEmpty() || residents.size() > CombatPackets.MAX_MEMBERS
                    || !(player.serverLevel().getEntity(targetId) instanceof Mob target)
                    || player.distanceToSqr(target) > 128 * 128) { return false; }
            List<Body> people = new LinkedHashSet<>(residents).stream()
                .map(id -> selectedBody(player, colony, id)).toList();
            if (people.stream().anyMatch(body -> !Threats.explicitlyAttackable(target, body.mob(), player))) { return false; }
            UnitGroup group = UnitGroups.of(colony).attackSelection(people, target, player);
            CombatSelectionOwnership.of(colony).trackGroup(player, group);
            return true;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return false;
        }
    }

    /** Stop retains selection and squad ownership, even if no safe local reformation is possible. */
    public static boolean stop(ServerPlayer player, UUID colonyId, List<UUID> residents) {
        try {
            Colony colony = authorized(player, colonyId).orElseThrow(() -> new IllegalArgumentException("unauthorized"));
            if (residents.isEmpty() || residents.size() > CombatPackets.MAX_MEMBERS) { return false; }
            List<Body> people = new LinkedHashSet<>(residents).stream().map(id -> selectedBody(player, colony, id)).toList();
            UnitGroup group = UnitGroups.of(colony).stopSelection(people);
            CombatSelectionOwnership.of(colony).trackGroup(player, group);
            return true;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return false;
        }
    }

    private static void trackGroup(ServerPlayer player, Colony colony, List<Body> people) {
        UnitGroup group = UnitGroups.of(colony).ofResident(people.getFirst().id()).orElseThrow();
        CombatSelectionOwnership.of(colony).trackGroup(player, group);
    }

    static Set<UUID> roster(ServerPlayer player, Colony colony) {
        Set<UUID> result = new LinkedHashSet<>();
        for (var resident : colony.residents()) {
            body(player, colony, resident.id()).filter(body -> CombatSelectionOwnership.of(colony).canSelect(player, body))
                .ifPresent(body -> result.add(body.id()));
        }
        return Set.copyOf(result);
    }

    private static Body selectedBody(ServerPlayer player, Colony colony, UUID id) {
        return body(player, colony, id).filter(body -> CombatSelectionOwnership.of(colony).selected(player, body))
            .orElseThrow(() -> new IllegalArgumentException("resident is not selected by this player"));
    }

    private static Optional<Body> body(ServerPlayer player, Colony colony, UUID id) {
        return Optional.ofNullable(player.serverLevel().getEntity(id)).flatMap(Bodies::of)
            .filter(body -> body.kind().id().equals(PersonBody.ID) && body.mob().isAlive() && !body.mob().isRemoved()
                && !body.mob().isPassenger() && body.colonyId().filter(colony.id()::equals).isPresent()
                && colony.holdingOf(id).isPresent()
                && WorldSpaces.at(body.mob()).sameRealm(WorldSpaces.at(player)));
    }

    private static void validPoint(ServerPlayer player, WorldPos point) {
        var level = player.serverLevel();
        if (!point.sameRealm(WorldSpaces.at(player)) || !point.in(level)) {
            throw new IllegalArgumentException("wrong realm");
        }
        BlockPos feet = point.block(level);
        if (!level.hasChunksAt(feet.offset(-1, -1, -1), feet.offset(1, 2, 1))
                || !Footing.withFeetAt(level, feet) || !level.getFluidState(feet).isEmpty()
                || !level.getFluidState(feet.above()).isEmpty()
                || WorldSpaces.world(level, point).filter(at -> at.distanceToSqr(player.position()) <= 128 * 128).isEmpty()) {
            throw new IllegalArgumentException("unsafe or distant destination");
        }
    }

}
