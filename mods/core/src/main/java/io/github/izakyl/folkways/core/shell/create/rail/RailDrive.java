package io.github.izakyl.folkways.core.shell.create.rail;

import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.RoutineCards;
import io.github.izakyl.folkways.core.facility.api.RuntimeFacilities;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.shell.create.rail.domain.Boarding;
import io.github.izakyl.folkways.core.shell.create.transport.ContraptionSeats;
import io.github.izakyl.folkways.core.transport.api.TrainControl;
import io.github.izakyl.folkways.core.work.api.Seating;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public record RailDrive(CarriageContraptionEntity entity, Train train) implements Signal, TrainControl {

    public static void install() {
        RuntimeFacilities.register(RailDrive::facility);
        Controls.register(ResourceLocation.parse("folkways_core:rail/drive"), Controls.SPECIFIC,
            (level, cell) -> !cell.in(level) ? List.of()
                : level.getEntitiesOfClass(CarriageContraptionEntity.class, new AABB(cell.block(level)).inflate(2),
                    entity -> WorldSpaces.at(entity).equals(cell)).stream().flatMap(entity -> drive(entity).stream())
                    .<Signal>map(drive -> drive).toList());
        Seating.register(ResourceLocation.parse("folkways_core:rail/seats"), Seating.SPECIFIC, new RailSeating());
    }

    private static Optional<RailDrive> drive(CarriageContraptionEntity entity) {
        var carriage = entity.getCarriage();
        return carriage == null || carriage.train == null ? Optional.empty()
            : Optional.of(new RailDrive(entity, carriage.train));
    }

    private static Optional<RuntimeFacilities.RuntimeFacility> facility(Colony colony, Entity entity) {
        if (!(entity instanceof CarriageContraptionEntity carriage)) {
            return Optional.empty();
        }
        RailDrive drive = drive(carriage).orElse(null);
        if (drive == null || !RailOperations.get().holds(colony, drive.train.id)) {
            return Optional.empty();
        }
        List<CarriageContraptionEntity> loaded = new ArrayList<>();
        drive.train.carriages.forEach(car -> car.forEachPresentEntity(each -> {
            if (each.isAlive() && each.level() == entity.level()
                    && ((ServerLevel) each.level()).getEntity(each.getUUID()) == each) {
                loaded.add(each);
            }
        }));
        if (loaded.isEmpty() || loaded.getFirst() != carriage) {
            return Optional.empty();
        }
        Map<String, Control> controls = new LinkedHashMap<>();
        controls.put("destination", drive);
        List<Stances.Seat> seats = new ArrayList<>();
        List<String> cargoPorts = new ArrayList<>();
        for (var each : loaded) {
            if (RailCargo.hasStorage(each.getCarriage())) {
                String port = "cargo_%08d".formatted(cargoPorts.size());
                controls.put(port, new RailCargo(each));
                cargoPorts.add(port);
            }
            if (each.getContraption() instanceof CarriageContraption contraption) {
                for (int index = 0; index < contraption.getSeats().size(); index++) {
                    seats.add(new Stances.Seat(each.getUUID(), index));
                }
            }
        }
        return Optional.of(new RuntimeFacilities.RuntimeFacility(
            RoutineCards.destinationAndDeposit(drive.train.id, "train_" + drive.train.id, drive.anchor(), controls,
                "destination", cargoPorts, loaded.stream().map(each -> each.getUUID()).toList()), Component.translatable("folkways.create.train.facility"), true, seats,
            Optional.empty()));
    }

    @Override
    public boolean belongsToTrain(UUID trainId) {
        return train.id.equals(trainId);
    }

    @Override
    public Optional<UUID> entityId() {
        return Optional.of(entity.getUUID());
    }

    @Override
    public WorldPos anchor() {
        return WorldSpaces.at(entity);
    }

    @Override
    public String part() {
        return "destination/" + train.id;
    }

    @Override
    public Object leaseKey() {
        return train.id;
    }

    @Override
    public boolean valid(ServerLevel level) {
        return entity.level() == level && level.getEntity(entity.getUUID()) == entity && entity.isAlive()
            && entity.getCarriage() != null && entity.getCarriage().train == train && train.graph != null
            && !train.derailed;
    }

    @Override
    public Optional<Stances> stance(ServerLevel level) {
        if (entity.getContraption() instanceof CarriageContraption contraption) {
            var seats = contraption.getSeats();
            for (int index = 0; index < seats.size(); index++) {
                if (contraption.conductorSeats.containsKey(seats.get(index))) {
                    return Optional.of(new Stances.Seat(entity.getUUID(), index));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Cost cost() {
        return Cost.REMOTE;
    }

    @Override
    public SignalType type() {
        return SignalType.DATA;
    }

    @Override
    public Optional<String> check(Value target) {
        return target instanceof Value.Text text && !text.value().isBlank()
            || Value.blockPosition(target).isPresent() ? Optional.empty()
            : Optional.of("expected station name or coordinates");
    }

    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        if (writer != null && (!(writer.getVehicle() instanceof CarriageContraptionEntity carriage)
                || carriage.getCarriage() == null || carriage.getCarriage().train != train
                || !(carriage.getContraption() instanceof CarriageContraption contraption)
                || contraption.conductorSeats.keySet().stream().map(contraption.getSeats()::indexOf)
                    .noneMatch(index -> index >= 0 && ContraptionSeats.occupant(carriage, index)
                        .filter(writer.getUUID()::equals).isPresent()))) {
            return Write.REFUSED;
        }
        Value target = value;
        if (!valid(level) || check(target).isPresent() || train.navigation.isActive()) {
            return Write.REFUSED;
        }
        var stations = train.graph.getPoints(EdgePointType.STATION).stream()
            .filter(station -> level.dimension().equals(station.getBlockEntityDimension()));
        GlobalStation station;
        if (target instanceof Value.Text text) {
            station = stations.filter(candidate -> candidate.name.equals(text.value()))
                .min(Comparator.comparing(candidate -> candidate.id)).orElse(null);
        } else {
            Vec3 point = Vec3.atCenterOf(Value.blockPosition(target).orElseThrow());
            station = stations.min(Comparator.<GlobalStation>comparingDouble(candidate ->
                Vec3.atCenterOf(candidate.getBlockEntityPos()).distanceToSqr(point))
                .thenComparing(candidate -> candidate.id)).orElse(null);
        }
        if (station == null) {
            return Write.REFUSED;
        }
        if (station == train.getCurrentStation()) {
            return Write.WRITTEN;
        }
        var path = train.navigation.findPathTo(station, Double.MAX_VALUE);
        if (path == null) {
            return Write.REFUSED;
        }
        var schedule = train.runtime.getSchedule();
        if (schedule != null && !train.runtime.paused) {
            for (int index = 0; index < schedule.entries.size(); index++) {
                if (schedule.entries.get(index).instruction instanceof DestinationInstruction destination
                        && destination.getFilter().equals(station.name)) {
                    train.runtime.currentEntry = index;
                    train.runtime.state = ScheduleRuntime.State.PRE_TRANSIT;
                    return Write.WRITTEN;
                }
            }
        }
        if (train.navigation.startNavigation(path) == -1) {
            return Write.REFUSED;
        }
        if (schedule != null) {
            train.runtime.paused = true;
        }
        return Write.WRITTEN;
    }

    private static final class RailSeating implements Seating.Adapter {

        @Override
        public boolean seats(Entity seat) {
            return seat instanceof CarriageContraptionEntity;
        }

        @Override
        public Set<WorldPos> footings(ServerLevel level, Entity seat, int index, Mob body) {
            return ContraptionSeats.position((CarriageContraptionEntity) seat, index)
                .flatMap(at -> Boarding.footings(level, at.cell(), Mth.ceil(Reach.PLAYER_BLOCK_REACH)))
                .map(Stances.Cells::cells).orElse(Set.of());
        }

        @Override
        public boolean in(Entity seat, int index, Mob body) {
            return ContraptionSeats.occupant((CarriageContraptionEntity) seat, index)
                .filter(body.getUUID()::equals).isPresent();
        }

        @Override
        public boolean sit(Entity seat, int index, Mob body) {
            return ContraptionSeats.board((CarriageContraptionEntity) seat, index, body);
        }
    }
}
