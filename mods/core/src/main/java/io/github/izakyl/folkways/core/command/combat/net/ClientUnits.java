package io.github.izakyl.folkways.core.command.combat.net;

import io.github.izakyl.folkways.core.card.client.ClientCards;
import io.github.izakyl.folkways.core.card.net.CardStatus;
import io.github.izakyl.folkways.core.combat.api.FireControl;
import io.github.izakyl.folkways.core.combat.api.VehicleProfile;
import io.github.izakyl.folkways.core.combat.api.VehicleProfiles;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.Util;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client data access for unit windows. Call refresh while open; every mutation returns a receipt id. */
public final class ClientUnits {
    private static final UUID NONE = new UUID(0, 0);
    private static final Vehicles.Order STOP = new Vehicles.Order(Vehicles.Action.STOP, List.of(), -1,
        Optional.empty(), Optional.empty());
    private static long sequence;
    private static long received;
    private static UUID colony;
    private static UnitSnapshot snapshot;
    private static final Map<Long, UnitReply> RECEIPTS = new LinkedHashMap<>();

    private static final Map<UUID, Long> FOCUS = new LinkedHashMap<>();

    private ClientUnits() {
    }

    public static Optional<UnitSnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public static List<UnitSnapshot.Unit> units() {
        return snapshot().map(UnitSnapshot::units).orElse(List.of());
    }

    public static Optional<UnitSnapshot.Unit> unit(UUID id) {
        return units().stream().filter(unit -> unit.id().equals(id)).findFirst();
    }

    public static Optional<UnitReply> receipt(long request) {
        return Optional.ofNullable(RECEIPTS.get(request));
    }

    public static long refresh(UUID colonyId) {
        if (!colonyId.equals(colony)) {
            clear();
            colony = colonyId;
        }
        return send(NONE, UnitRequest.Operation.ROSTER, List.of(), -1, -1, STOP, "", WeaponRules.DEFAULT);
    }

    public static long board(UUID unit, List<UUID> residents) {
        return send(unit, UnitRequest.Operation.BOARD, residents, -1, -1, STOP, "", WeaponRules.DEFAULT);
    }

    public static long assign(UUID unit, UUID resident, int seat) {
        return send(unit, UnitRequest.Operation.ASSIGN, List.of(resident), seat, -1, STOP, "", WeaponRules.DEFAULT);
    }

    /** Seat -1 means all seats. */
    public static long leave(UUID unit, int seat) {
        return send(unit, UnitRequest.Operation.LEAVE, List.of(), seat, -1, STOP, "", WeaponRules.DEFAULT);
    }

    public static long order(UUID unit, Vehicles.Order order) {
        return send(unit, UnitRequest.Operation.ORDER, List.of(), -1, -1, order, "", WeaponRules.DEFAULT);
    }

    /** Commits one waypoint against the current server queue without replaying completed legs. */
    public static long appendPoint(UUID unit, WorldPos point,
                                   boolean attackMove) {
        return send(unit, UnitRequest.Operation.APPEND, List.of(), -1, -1,
            new Vehicles.Order(attackMove ? Vehicles.Action.ATTACK_MOVE : Vehicles.Action.MOVE,
                List.of(point), -1, Optional.empty(), Optional.empty()), "", WeaponRules.DEFAULT);
    }

    public static long cycle(UUID unit, int start) {
        return send(unit, UnitRequest.Operation.CYCLE, List.of(), -1, start, STOP, "", WeaponRules.DEFAULT);
    }

    public static long selectWeapon(UUID unit, int seat, int index) {
        return send(unit, UnitRequest.Operation.WEAPON, List.of(), seat, index, STOP, "", WeaponRules.DEFAULT);
    }

    /** Vehicle key: seatIndex/weaponName. Squad key: Loadouts provider id, or minecraft:melee. */
    public static long weaponRule(UUID unit, String weapon, WeaponRules.Rule rule) {
        return send(unit, UnitRequest.Operation.RULE, List.of(), -1, -1, STOP, weapon, rule);
    }

    public static List<VehicleProfile> vehicleProfiles() {
        return snapshot().map(UnitSnapshot::vehicleProfiles).orElse(List.of());
    }

    public static Optional<VehicleProfile> vehicleProfile(UUID unit) {
        return unit(unit).flatMap(UnitSnapshot.Unit::vehicle).flatMap(vehicle -> vehicleProfiles().stream()
            .filter(profile -> profile.id().equals(vehicle.profile())).findFirst());
    }

    public record InventoryLine(ItemSpec items, int current, int target, int threshold) {
        public int deficit() {
            return Math.max(0, target - current);
        }
    }

    public static List<InventoryLine> vehicleInventory(UUID unit) {
        var profile = vehicleProfile(unit);
        var readiness = unit(unit).flatMap(UnitSnapshot.Unit::vehicle);
        if (profile.isEmpty() || readiness.isEmpty()) {
            return List.of();
        }
        List<InventoryLine> lines = new ArrayList<>();
        var stock = profile.orElseThrow().stock();
        var current = readiness.orElseThrow().stock();
        for (int n = 0; n < stock.size(); n++) {
            var entry = stock.get(n);
            lines.add(new InventoryLine(entry.items(), n < current.size() ? current.get(n) : 0,
                entry.target(), entry.threshold()));
        }
        return List.copyOf(lines);
    }

    public static void editVehicleProfiles() {
        Pages.open(VehicleProfiles.ID);
    }

    public static long bindVehicleProfile(UUID unit, UUID profile) {
        return send(unit, UnitRequest.Operation.PROFILE_BIND, List.of(profile), -1, -1, STOP, "", WeaponRules.DEFAULT);
    }

    public static long releaseWhenIdle(UUID unit, boolean on) {
        return send(unit, UnitRequest.Operation.RELEASE_IDLE, List.of(), -1, on ? 1 : 0, STOP, "", WeaponRules.DEFAULT);
    }

    public static long releaseOwnership(UUID unit) {
        return send(unit, UnitRequest.Operation.RELEASE_OWNERSHIP, List.of(), -1, -1, STOP, "", WeaponRules.DEFAULT);
    }

    public static long altitude(UUID unit, Vehicles.Altitude band) {
        return send(unit, UnitRequest.Operation.ALTITUDE, List.of(), -1, band.ordinal(), STOP, "",
            WeaponRules.DEFAULT);
    }

    public static long targetFacility(UUID facility, int index, String routine, Optional<UUID> entity,
                                    Optional<WorldPos> position) {
        return targetFacility(facility, index, routine, entity, position.stream().toList());
    }

    /** A target picked as several places, such as a box's two corners. */
    public static long targetFacility(UUID facility, int index, String routine, Optional<UUID> entity,
                                    List<WorldPos> positions) {
        var order = new Vehicles.Order(Vehicles.Action.STOP, List.copyOf(positions), -1, entity, Optional.empty());
        return send(facility, UnitRequest.Operation.FACILITY_TARGET, List.of(), -1, index, order, routine,
            WeaponRules.DEFAULT);
    }

    /** The last live status the server sent for a selected facility. */
    public static Optional<CardStatus> facilityStatus(UUID facility) {
        return ClientCards.status(facility).filter(status -> status.colony().equals(colony));
    }

    private static long send(UUID unit, UnitRequest.Operation operation, List<UUID> residents, int seat, int index,
                             Vehicles.Order order, String weapon, WeaponRules.Rule rule) {
        if (colony == null) {
            throw new IllegalStateException("refresh the colony before sending unit commands");
        }
        long request = ++sequence;
        PacketDistributor.sendToServer(new UnitRequest(request, colony, unit, operation, residents,
            seat, index, order, weapon, rule, Optional.empty()));
        return request;
    }

    static void accept(UnitReply reply) {
        if (!reply.colony().equals(colony)) {
            return;
        }
        RECEIPTS.put(reply.request(), reply);
        while (RECEIPTS.size() > 128) {
            RECEIPTS.remove(RECEIPTS.keySet().iterator().next());
        }
        if (reply.request() >= received) {
            received = reply.request();
            snapshot = reply.snapshot().orElse(null);
        }
    }

    static void accept(FocusMark mark) {
        if (mark.colony().equals(colony)) {
            FOCUS.put(mark.target(), Util.getMillis() + 50L * FireControl.FOCUS_TICKS);
        }
    }

    public static Set<UUID> focusMarks() {
        long now = Util.getMillis();
        FOCUS.values().removeIf(until -> now >= until);
        return Set.copyOf(FOCUS.keySet());
    }

    public static void clear() {
        colony = null;
        snapshot = null;
        RECEIPTS.clear();
        ClientCards.forget();
        FOCUS.clear();
        received = 0;
    }
}
