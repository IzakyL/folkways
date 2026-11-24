package io.github.izakyl.folkways.vanilla.warfare;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.Post;
import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.shell.warfare.VehicleBoarding;
import io.github.izakyl.folkways.core.shell.warfare.VehicleSeats;
import io.github.izakyl.folkways.core.shell.warfare.WarfareAmmo;
import io.github.izakyl.folkways.core.shell.warfare.WarfareDrive;
import io.github.izakyl.folkways.core.shell.warfare.WarfareMount;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/** A native seat with an approach chosen for its assigned resident. */
final class VehiclePost implements Post.Station {
    private final VehicleEntity vehicle;
    private final int seat;
    private final UUID assigned;
    private final Stances approach;
    private final boolean reachable;
    private boolean seatUnavailable;
    private long waitingSince = -1;

    VehiclePost(VehicleEntity vehicle, int seat, Mob body) {
        this.vehicle = vehicle;
        this.seat = seat;
        assigned = body == null ? null : body.getUUID();
        boolean aboard = body != null && VehicleSeats.holds(vehicle, body);
        var destination = body == null || aboard ? Optional.<BlockPos>empty()
            : VehicleBoarding.footing(vehicle, body);
        reachable = aboard || destination.isPresent();
        approach = aboard ? Stances.WHEREVER
            : Stances.at(vehicle.level(), destination.orElse(vehicle.blockPosition()));
    }

    Post post(ServerLevel level) {
        var stance = new Stances.Seat(vehicle.getUUID(), seat);
        return new Post(VehicleSeats.postKey(vehicle, seat),
            new WorkSite.AtSeat(vehicle.getUUID(), seat, WorldSpaces.at(vehicle)), stance,
            ref -> Controls.resolve(level, ref)
                .filter(control -> control.entityId().filter(vehicle.getUUID()::equals).isPresent())
                .filter(control -> control instanceof WarfareAmmo
                    || control.stance(level).filter(stance::equals).isPresent()).isPresent(),
            new Post.Demand(Post.Staffing.STANDING, Urge.Priority.CONTROL, true), this);
    }

    @Override
    public void describe(NodeSpec.Builder spec) {
        spec.vocation(Vocations.required(WarfareCrew.CREW)).effort(new WorkEffort.PerTick(1.0D / 20.0D));
    }

    @Override
    public Stances approach(Post post) {
        return approach;
    }

    @Override
    public Optional<UUID> worker() {
        return Optional.ofNullable(assigned);
    }

    @Override
    public boolean directTravel() {
        return true;
    }

    @Override
    public boolean dismountOnRelease() {
        return false;
    }

    @Override
    public boolean admits(Mob body) {
        return body.getUUID().equals(assigned);
    }

    boolean demanded(ServerLevel level) {
        return reachable && vehicle.level() == level && !vehicle.isRemoved() && !vehicle.isWreck()
            && VehicleSeats.assigned(vehicle, seat).filter(id -> id.equals(assigned)).isPresent();
    }

    @Override
    public Post.Boarding board(ServerLevel level, Post post, Mob body) {
        if (present(level, post, body)) {
            return Post.Boarding.SEATED;
        }
        // Duty navigation brings the assigned resident to the boarding approach.
        if (body.isPassenger() || vehicle.getLocked() || vehicle.getNthEntity(seat) != null
                || !WorldSpaces.at(body).sameRealm(WorldSpaces.at(vehicle))) {
            return giveUp();
        }
        if (waitingSince < 0) {
            waitingSince = level.getGameTime();
        }
        if (level.getGameTime() - waitingSince >= 100) {
            return giveUp();
        }
        if (!body.startRiding(vehicle)) {
            return Post.Boarding.WAITING;
        }
        if (vehicle.getSeatIndex(body) != seat && !vehicle.changeSeat(body, seat)) {
            body.stopRiding();
            return giveUp();
        }
        body.setTarget(null);
        return Post.Boarding.SEATED;
    }

    private Post.Boarding giveUp() {
        seatUnavailable = true;
        if (VehicleSeats.assigned(vehicle, seat).filter(id -> id.equals(assigned)).isPresent()) {
            VehicleSeats.release(vehicle, seat);
        }
        return Post.Boarding.REFUSED;
    }

    @Override
    public boolean present(ServerLevel level, Post post, Mob body) {
        return vehicle.getSeatIndex(body) == seat && VehicleSeats.holds(vehicle, body);
    }

    @Override
    public Set<ControlRef> retainedControls() {
        return seat == 0 && !WarfareMount.emplacement(vehicle) ? Set.of(new WarfareDrive(vehicle).ref()) : Set.of();
    }

    @Override
    public Outcome finish(Worker worker, boolean staffed, boolean demanded) {
        return seatUnavailable ? Outcome.failed(LaborRefusal.NO_WAY_THERE) : Outcome.done();
    }
}
