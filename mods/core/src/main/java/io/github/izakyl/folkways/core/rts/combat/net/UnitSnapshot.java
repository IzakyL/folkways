package io.github.izakyl.folkways.core.rts.combat.net;

import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.VehicleProfile;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;

/** UI-facing immutable values, with no SW or client rendering dependencies. */
public record UnitSnapshot(UUID colony, List<Unit> units, List<Resident> residents,
                           List<LandingSites.Site> landingSites, List<VehicleProfile> vehicleProfiles) {
    public enum Kind { SQUAD, VEHICLE }

    public record Unit(UUID id, Kind kind, Component name, WorldPos position, Optional<UUID> owner,
                       List<UUID> members, List<Vehicles.Seat> seats, Vehicles.Capabilities capabilities,
                       Vehicles.Status status, Map<String, Component> weapons,
                       Map<String, WeaponRules.Rule> weaponRules, Optional<Vehicles.Readiness> vehicle,
                       boolean releaseWhenIdle, Vehicles.Altitude altitude) {
        public Unit {
            members = List.copyOf(members);
            seats = List.copyOf(seats);
            weapons = Map.copyOf(weapons);
            weaponRules = Map.copyOf(weaponRules);
        }
    }

    public record Resident(UUID id, Component name, boolean commanded, Optional<UUID> vehicle) {
    }

    public UnitSnapshot {
        vehicleProfiles = List.copyOf(vehicleProfiles);
        units = List.copyOf(units);
        residents = List.copyOf(residents);
        landingSites = List.copyOf(landingSites);
    }
}
