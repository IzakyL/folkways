package io.github.izakyl.folkways.create.elevator;

import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.create.FolkwaysCreateConfig;
import io.github.izakyl.folkways.create.transport.SeatedConveyance;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

final class ElevatorConveyance extends SeatedConveyance<Integer> {

    private final ElevatorFare fare;
    private final Stances.Cells boarding;
    private UUID rider;
    private boolean requested;
    private boolean rejected;

    ElevatorConveyance(ElevatorFare fare, Stances.Cells boarding, Stances.Cells landing) {
        super(boarding, landing, FolkwaysCreateConfig::seatTicks,
            ElevatorPlugin.BOARDING, ElevatorPlugin.RIDING, ElevatorRefusal.UNAVAILABLE);
        this.fare = fare;
        this.boarding = boarding;
    }

    private Optional<ElevatorController> controller(ServerLevel level) {
        return ElevatorDispatch.controller(level.getServer(), fare.colony(), fare.lift());
    }

    @Override
    public Going step(ServerLevel level, Mob body, Set<WorldPos> to) {
        rider = body.getUUID();
        ElevatorController lift = controller(level).orElse(null);
        if (lift != null) {
            Set<BlockPos> cells = new LinkedHashSet<>();
            for (WorldPos cell : boarding.cells()) {
                if (cell.in(level)) {
                    cells.add(cell.block(level));
                }
            }
            if (Reach.stanceHolding(body.position(), cells).isPresent()) {
                if (!requested && !rejected && lift.view().isPresent()) {
                    requested = lift.request(rider, fare.from(), fare.to(), level.getGameTime());
                    rejected = !requested;
                }
                if (requested) {
                    lift.touch(rider, level.getGameTime());
                }
            }
        }
        return super.step(level, body, to);
    }

    @Override
    protected boolean available(ServerLevel level) {
        return level.dimension().equals(fare.lift().dimension()) && controller(level).isPresent();
    }

    @Override
    protected Optional<RefusalKind> boardingFailure(ServerLevel level) {
        return rejected || requested && controller(level).map(lift -> lift.status(rider)
                == ElevatorController.Status.FAILED).orElse(true)
            ? Optional.of(ElevatorRefusal.RIDE_CANCELLED) : Optional.empty();
    }

    @Override
    protected Going awaitVehicle(ServerLevel level) {
        return requested && controller(level).map(lift -> lift.status(rider)
            == ElevatorController.Status.BOARDING).orElse(false) ? Going.ARRIVED : Going.UNDERWAY;
    }

    @Override
    protected Optional<Integer> freeSeat(ServerLevel level) {
        return controller(level).flatMap(lift -> lift.seat(rider));
    }

    @Override
    protected boolean occupied(ServerLevel level, Integer seat) {
        return ElevatorDispatch.network().occupant(level.getServer(), fare.lift(), seat).isPresent();
    }

    @Override
    protected boolean board(ServerLevel level, Integer seat, Mob body) {
        return controller(level).map(lift -> lift.board(level.getServer(), rider, body)).orElse(false);
    }

    @Override
    protected boolean atDestination(ServerLevel level) {
        return controller(level).map(lift -> lift.status(rider) == ElevatorController.Status.ALIGHTING
            && lift.view().map(view -> view.stoppedAt().orElse(Integer.MIN_VALUE) == fare.to()).orElse(false))
            .orElse(false);
    }

    @Override
    protected Optional<Integer> seatOf(ServerLevel level, Mob body) {
        return ElevatorDispatch.network().seatOf(level.getServer(), fare.lift(), body);
    }

    @Override
    protected void disembark(ServerLevel level, Integer seat, Mob body) {
        controller(level).ifPresent(lift -> lift.alight(level.getServer(), rider, body));
    }

    @Override
    protected boolean canRelease(ServerLevel level) {
        // Cancellation in transit leaves the passenger aboard; the controller completes a safe drop-off.
        return atDestination(level);
    }

    @Override
    public void release(ServerLevel level, Mob body) {
        super.release(level, body);
        controller(level).ifPresent(lift -> lift.cancel(body.getUUID()));
    }
}
