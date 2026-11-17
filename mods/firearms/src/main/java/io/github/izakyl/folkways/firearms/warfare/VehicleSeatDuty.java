package io.github.izakyl.folkways.firearms.warfare;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.combat.api.Commands;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.pathfinder.Path;

/** The same labour runner first walks to the hull, then retains a continuous mounted duty. */
final class VehicleSeatDuty implements Node, Workload.Continuous {
    private final VehicleEntity vehicle;
    private final Worker worker;
    private final int seat;
    private final boolean aboard;
    private final NodeSpec spec;
    private final boolean reachable;
    private boolean seatUnavailable;
    private long waitingSince = -1;
    static final int PATH_LIMIT = 4;

    record Approach(BlockPos cell, Path path) {
    }

    VehicleSeatDuty(VehicleEntity vehicle, Worker worker, int seat, boolean aboard) {
        this.vehicle = vehicle;
        this.worker = worker;
        this.seat = seat;
        this.aboard = aboard;
        UUID id = UUID.nameUUIDFromBytes((vehicle.getUUID() + "/" + seat + "/" + worker.resident().id()
            + "/" + aboard).getBytes(StandardCharsets.UTF_8));
        Optional<BlockPos> destination = aboard ? Optional.empty() : footing(vehicle, worker.body());
        reachable = aboard || destination.isPresent();
        spec = NodeSpec.of(id, Commands.ORDERS,
            new WorkSite.AtSeat(vehicle.getUUID(), seat, WorldSpaces.at(vehicle)),
            aboard ? Stances.WHEREVER : Stances.at(vehicle.level(), destination.orElse(vehicle.blockPosition())),
            this)
            .vocation(Vocations.required(WarfareVehicles.CREW)).done();
    }

    static Optional<BlockPos> footing(VehicleEntity vehicle, Mob body) {
        return approach(vehicle, body).map(Approach::cell);
    }

    static Optional<Approach> approach(VehicleEntity vehicle, Mob body) {
        var candidates = footings(vehicle, body.getDimensions(body.getPose())).stream()
            .sorted(Comparator.<BlockPos>comparingDouble(pos -> world(vehicle, pos).distanceToSqr(body.position()))
                .thenComparingLong(BlockPos::asLong)).limit(PATH_LIMIT).toList();
        for (BlockPos pos : candidates) {
            var path = body.getNavigation().createPath(pos, 0);
            if (path != null && path.canReach()) {
                return Optional.of(new Approach(pos, path));
            }
        }
        // Ground navigation can be unavailable while a newly spawned/dismounted resident settles.
        return candidates.stream().findFirst().map(pos -> new Approach(pos, null));
    }

    static boolean hasFooting(VehicleEntity vehicle, Mob body) {
        return !footings(vehicle, body.getDimensions(body.getPose())).isEmpty();
    }

    static Vec3 world(VehicleEntity vehicle, BlockPos storage) {
        return WorldSpaces.world(vehicle.level(), WorldSpaces.at(vehicle.level(), storage))
            .orElse(Vec3.atBottomCenterOf(storage));
    }

    static Vec3 world(VehicleEntity vehicle, Vec3 storage) {
        return WorldSpaces.containing(vehicle.level(), BlockPos.containing(storage))
            .map(frame -> frame.toWorld(storage.subtract(Vec3.atLowerCornerOf(frame.storageOrigin()))))
            .orElse(storage);
    }

    static List<BlockPos> footings(VehicleEntity vehicle) {
        return footings(vehicle, EntityDimensions.fixed(.6f, 1.8f));
    }

    private static List<BlockPos> footings(VehicleEntity vehicle, EntityDimensions dimensions) {
        var hull = vehicle.getBoundingBox();
        WorldPos at = WorldSpaces.at(vehicle);
        BlockPos centre = WorldSpaces.storage(vehicle.level(), at).orElse(vehicle.blockPosition());
        int radius = (int) Math.ceil(vehicle.getBbWidth()) + 1;
        return BlockPos.betweenClosedStream(centre.offset(-radius, -1, -radius), centre.offset(radius, 1, radius))
            .filter(pos -> vehicle.level().hasChunkAt(pos) && Footing.withFeetAt(vehicle.level(), pos))
            .filter(pos -> WorldSpaces.at(vehicle.level(), pos).sameRealm(at))
            .filter(pos -> {
                Vec3 feet = world(vehicle, pos);
                return !hull.inflate(.1).intersects(dimensions.makeBoundingBox(feet))
                    && hull.distanceToSqr(feet) <= 9;
            })
            .map(BlockPos::immutable).toList();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public Optional<UUID> worker() {
        return Optional.of(worker.resident().id());
    }

    @Override
    public boolean mountedWork() {
        return true;
    }

    @Override
    public boolean directTravel() {
        return true;
    }

    @Override
    public boolean ready(ServerLevel level) {
        return reachable && vehicle.level() == level && !vehicle.isRemoved() && !vehicle.isWreck()
            && VehicleSeats.assigned(vehicle, seat).filter(worker.resident().id()::equals).isPresent();
    }

    @Override
    public boolean arrived(ServerLevel level, io.github.izakyl.folkways.core.resident.api.Body body,
                           Stances footing, boolean atStance) {
        return aboard ? VehicleSeats.holds(vehicle, body, seat) : atStance;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return seatUnavailable ? Outcome.failed(LaborRefusal.NO_WAY_THERE) : Outcome.done();
    }

    private boolean nearFooting(Mob body) {
        return footings(vehicle, body.getDimensions(body.getPose())).stream().anyMatch(pos -> {
            Vec3 delta = body.position().subtract(world(vehicle, pos));
            return delta.horizontalDistanceSqr() <= Reach.IN_THE_CELL * Reach.IN_THE_CELL
                && Math.abs(delta.y) <= Reach.CELL_HEIGHT;
        });
    }

    private boolean giveUp() {
        seatUnavailable = true;
        if (VehicleSeats.assigned(vehicle, seat).filter(worker.resident().id()::equals).isPresent()) {
            VehicleSeats.release(vehicle, seat);
        }
        return false;
    }

    @Override
    public boolean holds(ServerLevel level, Worker who) {
        if (aboard) {
            return ready(level) && Bodies.of(who.body())
                .filter(body -> VehicleSeats.holds(vehicle, body, seat)).isPresent();
        }
        // Use the same frame-aware arrival envelope as walking, including its vertical tolerance.
        // A fixed hull distance can reject a legitimate stance after the native hull bounds settle.
        if (!ready(level) || who.body().isPassenger() || vehicle.getLocked()
            || vehicle.getNthEntity(seat) != null
            || !nearFooting(who.body())
            || !WorldSpaces.at(who.body()).sameRealm(WorldSpaces.at(vehicle))) {
            return giveUp();
        }
        if (waitingSince < 0) {
            waitingSince = level.getGameTime();
        }
        if (level.getGameTime() - waitingSince >= 100) {
            return giveUp();
        }
        // Native dismount cooldown may refuse a nearby mount temporarily.
        if (!who.body().startRiding(vehicle)) {
            return true;
        }
        if (vehicle.getSeatIndex(who.body()) != seat && !vehicle.changeSeat(who.body(), seat)) {
            who.body().stopRiding();
            giveUp();
        } else {
            who.body().setTarget(null);
        }
        return false;
    }
}
