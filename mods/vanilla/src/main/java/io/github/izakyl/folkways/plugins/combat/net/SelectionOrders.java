package io.github.izakyl.folkways.plugins.combat.net;

import io.github.izakyl.folkways.core.api.colony.Books;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.plugins.combat.group.Deployment;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroups;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Authoritative selection commands; clients submit intent, never navigation or membership edits. */
public final class SelectionOrders {
    private SelectionOrders() { }

    static Optional<Colony> authorized(ServerPlayer player, UUID expected) {
        if (!player.getServer().isSameThread()) { return Optional.empty(); }
        return Pages.colonyOf(player).filter(colony -> colony.id().equals(expected))
            .filter(colony -> Books.ofHeldBook(player, expected).filter(held -> held.id().equals(expected)).isPresent());
    }

    public static boolean submit(ServerPlayer player, UUID colonyId, List<UUID> residents,
            List<WorldPos> points, boolean patrol) {
        try {
            Colony colony = authorized(player, colonyId).orElseThrow(() -> new IllegalArgumentException("unauthorized"));
            if (residents.isEmpty() || residents.size() > CombatPackets.MAX_MEMBERS
                    || points.isEmpty() || points.size() > CombatPackets.MAX_POINTS
                    || (patrol ? points.size() < 2 : points.size() != 1)) {
                return false;
            }
            // Duplicate identifiers are harmless; every distinct selected person is retained.
            List<Body> people = new LinkedHashSet<>(residents).stream()
                .map(id -> body(player, colony, id).orElseThrow(() -> new IllegalArgumentException("unavailable"))).toList();
            Vec3 front = front(player, people, points.getFirst());
            List<Deployment> route = points.stream().map(point -> compact(point, front, people.size())).toList();
            for (int n = 0; n < route.size(); n++) {
                validPoint(player, points.get(n));
                for (WorldPos cell : route.get(n).line(people.size())) { validPoint(player, cell); }
            }
            UnitGroups.of(colony).commandSelection(people, route, patrol);
            return true;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return false;
        }
    }

    static Set<UUID> roster(ServerPlayer player, Colony colony) {
        Set<UUID> result = new LinkedHashSet<>();
        for (var resident : colony.residents()) {
            body(player, colony, resident.id()).ifPresent(body -> result.add(body.id()));
        }
        return Set.copyOf(result);
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

    private static Vec3 front(ServerPlayer player, List<Body> people, WorldPos target) {
        double x = people.stream().mapToDouble(body -> WorldSpaces.at(body.mob()).cell().getX()).average().orElse(0);
        double z = people.stream().mapToDouble(body -> WorldSpaces.at(body.mob()).cell().getZ()).average().orElse(0);
        double dx = target.cell().getX() - x;
        double dz = target.cell().getZ() - z;
        if (dx * dx + dz * dz < .01) {
            var direction = player.getDirection();
            return new Vec3(direction.getStepX(), 0, direction.getStepZ());
        }
        return Math.abs(dx) > Math.abs(dz) ? new Vec3(Math.signum(dx), 0, 0) : new Vec3(0, 0, Math.signum(dz));
    }

    private static Deployment compact(WorldPos at, Vec3 front, int count) {
        int columns = (int) Math.ceil(Math.sqrt(count));
        Vec3 side = new Vec3(-front.z, 0, front.x).scale(columns - 1);
        Vec3 center = Vec3.atBottomCenterOf(at.cell());
        return new Deployment(at.at(BlockPos.containing(center.subtract(side))),
            at.at(BlockPos.containing(center.add(side))), front, new Deployment.Formation(2, columns));
    }
}
