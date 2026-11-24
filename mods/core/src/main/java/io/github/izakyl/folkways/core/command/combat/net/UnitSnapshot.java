package io.github.izakyl.folkways.core.command.combat.net;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.VehicleProfile;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;

/** UI-facing immutable values, with no SW or client rendering dependencies. */
public record UnitSnapshot(UUID colony, List<Unit> units, List<Resident> residents,
                           List<LandingSites.Site> landingSites, List<VehicleProfile> vehicleProfiles) {
    public enum Kind { SQUAD, VEHICLE, FACILITY }

    public record Unit(UUID id, Kind kind, Component name, WorldPos position, Optional<UUID> owner,
                       List<UUID> members, List<Vehicles.Seat> seats, Vehicles.Capabilities capabilities,
                       Vehicles.Status status, Map<String, Component> weapons,
                       Map<String, WeaponRules.Rule> weaponRules, Optional<Vehicles.Readiness> vehicle,
                       boolean releaseWhenIdle, Vehicles.Altitude altitude, Optional<FacilityUnit> facility) {
        public Unit {
            members = List.copyOf(members);
            seats = List.copyOf(seats);
            weapons = Map.copyOf(weapons);
            weaponRules = Map.copyOf(weaponRules);
        }

        public Unit(UUID id, Kind kind, Component name, WorldPos position, Optional<UUID> owner,
                    List<UUID> members, List<Vehicles.Seat> seats, Vehicles.Capabilities capabilities,
                    Vehicles.Status status, Map<String, Component> weapons,
                    Map<String, WeaponRules.Rule> weaponRules, Optional<Vehicles.Readiness> vehicle,
                    boolean releaseWhenIdle, Vehicles.Altitude altitude) {
            this(id, kind, name, position, owner, members, seats, capabilities, status, weapons, weaponRules,
                vehicle, releaseWhenIdle, altitude, Optional.empty());
        }
    }

    /**
     * What a client needs to pick and command a player facility: its bounds, its declared card and which
     * background triggers are on. Readings, disabled buttons and runs follow separately, only while it is selected.
     */
    public record FacilityUnit(FacilityExtent extent, Card card, Map<String, Boolean> triggers,
                             Map<String, Value> parameters) {
        public FacilityUnit {
            triggers = Map.copyOf(triggers);
            parameters = Map.copyOf(parameters);
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
