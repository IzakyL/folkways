package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.work.api.Seating;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

/** Ground destinations for the hull's existing native controller. */
public record WarfareDrive(VehicleEntity vehicle) implements Signal {

    static final ResourceLocation ID = ResourceLocation.parse("folkways_core:warfare/drive");

    static List<Signal> at(ServerLevel level, WorldPos cell) {
        return !cell.in(level) ? List.of()
            : level.getEntitiesOfClass(VehicleEntity.class, new AABB(cell.block(level)),
                vehicle -> !WarfareMount.emplacement(vehicle) && WorldSpaces.at(vehicle).equals(cell)).stream()
                .<Signal>map(WarfareDrive::new).toList();
    }

    @Override
    public Optional<UUID> entityId() {
        return Optional.of(vehicle.getUUID());
    }

    @Override
    public WorldPos anchor() {
        return WorldSpaces.at(vehicle);
    }

    @Override
    public String part() {
        return "destination/" + vehicle.getUUID();
    }

    @Override
    public Object leaseKey() {
        return seat();
    }

    private Stances.Seat seat() {
        return new Stances.Seat(vehicle.getUUID(), 0);
    }

    @Override
    public Optional<Stances> stance(ServerLevel level) {
        return Optional.of(seat());
    }

    @Override
    public boolean valid(ServerLevel level) {
        return vehicle.level() == level && level.getEntity(vehicle.getUUID()) == vehicle
            && !vehicle.isWreck() && !vehicle.getLocked();
    }

    @Override
    public boolean admits(Mob writer) {
        return Bodies.of(writer).filter(body ->
            VehicleSeats.assigned(vehicle, 0).filter(body.id()::equals).isPresent()
                && VehicleSeats.owner(vehicle).equals(body.colonyId())).isPresent();
    }

    @Override
    public boolean enter(Mob writer) {
        return Seating.in(writer, seat()) && VehicleSeats.holds(vehicle, writer);
    }

    @Override
    public Cost cost() {
        return Cost.REMOTE;
    }

    @Override
    public SignalType type() {
        return SignalType.BLOCK_POSITION;
    }

    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        if (writer != null && !enter(writer)) {
            return Write.REFUSED;
        }
        BlockPos cell = Value.blockPosition(value).orElse(null);
        if (!valid(level) || cell == null) {
            return Write.REFUSED;
        }
        WorldPos destination = anchor().at(cell);
        if (WorldSpaces.world(level, destination)
            .filter(pos -> level.hasChunkAt(BlockPos.containing(pos))).isEmpty()) {
            return Write.REFUSED;
        }
        return WarfareVehicles.provider().destination(vehicle, destination).accepted() ? Write.WRITTEN : Write.REFUSED;
    }
}
