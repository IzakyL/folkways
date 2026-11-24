package io.github.izakyl.folkways.vanilla.create.elevator;

import io.github.izakyl.folkways.core.shell.create.elevator.CreateElevatorNetwork;
import io.github.izakyl.folkways.core.facility.api.Contribution;
import io.github.izakyl.folkways.core.facility.api.Facility;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.core.facility.api.Trigger;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.shell.create.elevator.ElevatorNetwork;
import io.github.izakyl.folkways.core.shell.create.elevator.ElevatorRef;
import io.github.izakyl.folkways.core.travel.api.Hop;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** A delegated pulley projects rides. The native shaft tracker also projects hazards for unowned cabins. */
record ElevatorDevice(ElevatorRef lift, UUID colony) implements Facility {

    @Override
    public FacilityExtent extent(ServerLevel level) {
        var parts = new java.util.LinkedHashSet<>(FacilityExtent.blocks(
            CreateElevatorNetwork.protectedParts(level, lift)).elements());
        CreateElevatorNetwork.cabin(level, lift).ifPresent(id -> parts.add(new FacilityExtent.Entity(id)));
        return new FacilityExtent(parts);
    }

    @Override
    public UUID id() {
        return lift.id();
    }

    @Override
    public WorldPos site() {
        return WorldPos.of(lift.dimension(), lift.pulley());
    }

    @Override
    public Map<String, Routine.Use> routines() {
        return Map.of();
    }

    @Override
    public List<Trigger> triggers() {
        return List.of();
    }

    @Override
    public Map<String, Value> parameters() {
        return Map.of();
    }

    @Override
    public Map<String, Reading> readings(ServerLevel level) {
        // A forecast is relative to the controller's clock and may change every tick while the lift moves.
        return Map.of("forecast_tick", world -> controller(world)
            .filter(controller -> controller.view().isPresent() && !controller.retired())
            .map(controller -> Value.of(controller.clock())),
            "parts_tick", world -> Optional.of(Value.of(world.getGameTime())));
    }

    @Override
    public List<Contribution> projections(ServerLevel level, Map<String, Value> readings) {
        List<Contribution> projected = new ArrayList<>();
        var parts = CreateElevatorNetwork.protectedParts(level, lift);
        if (!parts.isEmpty()) {
            projected.add(new Contribution.Protection(parts));
        }
        if (!readings.containsKey("forecast_tick")) {
            return List.copyOf(projected);
        }
        ElevatorController controller = controller(level).orElseThrow();
        ElevatorNetwork.View view = controller.view().orElseThrow();
        if (!view.powered() || view.places().isEmpty()) {
            return List.copyOf(projected);
        }
        ElevatorForecast forecast = controller.forecast();
        for (ElevatorNetwork.Floor from : view.floors()) {
            for (ElevatorNetwork.Floor to : view.floors()) {
                if (from.y() != to.y()) {
                    Hop hop = new Hop(ElevatorPlugin.ID, from.waiting(), to.platform(),
                        new ElevatorFare(colony, lift, from.y(), to.y(), forecast));
                    projected.add(new Contribution.Hop(hop));
                }
            }
        }
        return List.copyOf(projected);
    }

    private Optional<ElevatorController> controller(ServerLevel level) {
        return ElevatorDispatch.controller(level.getServer(), colony, lift);
    }
}
