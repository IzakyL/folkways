package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.RoutineCards;
import io.github.izakyl.folkways.core.facility.api.RuntimeFacilities;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.work.api.Seating;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

public final class WarfareIntegration {
    private WarfareIntegration() {
    }

    public static void install(IEventBus bus) {
        bus.addListener(Declaring.class, event -> Vehicles.register(WarfareVehicles.provider()));
        LandingSites.registerFacilities(VehicleCatapults::scan);
        RuntimeFacilities.register(WarfareIntegration::facility);
        Controls.register(WarfareDrive.ID, Controls.SPECIFIC, WarfareDrive::at);
        Controls.register(WarfareMount.ID, Controls.SPECIFIC, WarfareMount::at);
        Ports.register(ResourceKind.ITEM, WarfareMount.ID, Ports.SPECIFIC, (level, cell) -> {
            List<Port<ItemStack>> found = new ArrayList<>(WarfareAmmo.at(level, cell));
            found.addAll(WarfareBreech.at(level, cell));
            return found;
        });
        Seating.register(WarfareSeating.ID, Seating.SPECIFIC, new WarfareSeating());
        NeoForge.EVENT_BUS.addListener(WarfareVehicles::tick);
        NeoForge.EVENT_BUS.addListener(WarfareVehicles::left);
        NeoForge.EVENT_BUS.addListener(WarfareWeapons::ticked);
        NeoForge.EVENT_BUS.addListener(WarfareExplosions::joined);
    }
    private static Optional<RuntimeFacilities.RuntimeFacility> facility(Colony colony, Entity entity) {
        if (!(entity instanceof VehicleEntity vehicle) || vehicle.isWreck() || vehicle.getLocked()
                || VehicleSeats.owner(vehicle).filter(colony.id()::equals).isEmpty()) {
            return Optional.empty();
        }
        var provider = Vehicles.of(vehicle).orElseThrow();
        var seats = provider.seats(vehicle).stream().map(seat -> new Stances.Seat(vehicle.getUUID(), seat.index()))
            .toList();
        Map<String, Control> controls = new LinkedHashMap<>();
        WarfareDrive drive = WarfareMount.emplacement(vehicle) ? null : new WarfareDrive(vehicle);
        if (drive != null) {
            controls.put("destination", drive);
        }
        if (vehicle instanceof MortarEntity mortar) {
            controls.put("breech", new WarfareBreech(mortar));
        } else if (vehicle.getContainerSize() > 0) {
            controls.put("ammo", new WarfareAmmo(vehicle));
        }
        return Optional.of(new RuntimeFacilities.RuntimeFacility(
            RoutineCards.destinationAndDeposit(vehicle.getUUID(), "warfare_" + vehicle.getUUID(),
                WorldSpaces.at(vehicle), controls, "destination", List.of(), List.of(vehicle.getUUID())), vehicle.getDisplayName(), false, seats,
            Optional.of((player, routine) -> WarfareVehicles.provider().routineStarted(colony, player, vehicle,
                routine))));
    }
}
