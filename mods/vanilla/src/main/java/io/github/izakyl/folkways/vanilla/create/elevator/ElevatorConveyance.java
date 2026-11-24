package io.github.izakyl.folkways.vanilla.create.elevator;

import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.shell.create.elevator.ElevatorApproach;
import io.github.izakyl.folkways.core.shell.create.transport.PassengerLanding;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.walk.ResidentMoveControl;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/** Walking passengers remain ordinary, standing entities throughout their journey. */
final class ElevatorConveyance implements Conveyance {
    private final ElevatorFare fare;
    private final Stances.Cells boarding;
    private final Stances.Cells landing;
    private boolean requested;
    private Doing doing = Doing.open(Doings.WAITING);

    ElevatorConveyance(ElevatorFare fare, Stances.Cells boarding, Stances.Cells landing) {
        this.fare = fare;
        this.boarding = boarding;
        this.landing = landing;
    }

    @Override
    public Going step(ServerLevel level, Mob body, Set<WorldPos> to) {
        if (body.getMoveControl() instanceof ResidentMoveControl control) {
            control.centreForTransport();
        }
        var lift = ElevatorDispatch.controller(level.getServer(), fare.colony(), fare.lift()).orElse(null);
        if (lift == null || !level.dimension().equals(fare.lift().dimension())) {
            return new Going.Failed(ElevatorRefusal.UNAVAILABLE);
        }
        if (!requested) {
            if (!ElevatorApproach.atPlatform(level, body, boarding)) {
                ElevatorApproach.walkToPlatform(level, body, boarding);
                return Going.UNDERWAY;
            }
            requested = lift.request(body.getUUID(), fare.from(), fare.to(), level.getGameTime());
            if (!requested) { return new Going.Failed(ElevatorRefusal.RIDE_CANCELLED); }
        }
        lift.touch(body.getUUID(), level.getGameTime());
        PassengerLanding.at(level, landing, to).ifPresent(cell ->
            lift.exitThrough(body.getUUID(), Set.of(WorldPos.of(level, cell))));
        switch (lift.status(body.getUUID())) {
            case WAITING -> {
                doing = Doing.open(Doings.WAITING);
                if (!ElevatorApproach.atPlatform(level, body, boarding)) { ElevatorApproach.walkToPlatform(level, body, boarding); }
            }
            case BOARDING -> {
                doing = Doing.open(ElevatorPlugin.BOARDING);
                lift.board(level.getServer(), body.getUUID(), body);
            }
            case RIDING -> {
                doing = Doing.open(ElevatorPlugin.RIDING);
                body.getNavigation().stop();
            }
            case ALIGHTING -> {
                doing = Doing.open(ElevatorPlugin.BOARDING);
                lift.alight(level.getServer(), body.getUUID(), body);
                if (lift.status(body.getUUID()) == ElevatorController.Status.FINISHED) { return Going.ARRIVED; }
            }
            case FINISHED -> { return Going.ARRIVED; }
            case FAILED -> { return new Going.Failed(ElevatorRefusal.RIDE_CANCELLED); }
        }
        return Going.UNDERWAY;
    }

    @Override
    public Optional<Doing> doing() { return Optional.of(doing); }

    @Override
    public void release(ServerLevel level, Mob body) {
        // The controller owns a cancelled passenger until its normal walk out finishes at a landing.
        ElevatorDispatch.controller(level.getServer(), fare.colony(), fare.lift())
            .ifPresent(lift -> lift.cancel(body.getUUID()));
    }
}
