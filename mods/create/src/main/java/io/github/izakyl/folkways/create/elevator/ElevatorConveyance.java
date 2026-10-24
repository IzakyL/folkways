package io.github.izakyl.folkways.create.elevator;

import io.github.izakyl.folkways.core.api.resident.Conveyance;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Doing;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.create.transport.PassengerLanding;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
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
        var lift = ElevatorDispatch.controller(level.getServer(), fare.colony(), fare.lift()).orElse(null);
        if (lift == null || !level.dimension().equals(fare.lift().dimension())) {
            return new Going.Failed(ElevatorRefusal.UNAVAILABLE);
        }
        if (!requested) {
            if (!atPlatform(level, body)) {
                walkToPlatform(level, body);
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
                if (!atPlatform(level, body)) { walkToPlatform(level, body); }
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

    private Set<BlockPos> platform(ServerLevel level) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (WorldPos pos : boarding.cells()) { if (pos.in(level)) { cells.add(pos.block(level)); } }
        return cells;
    }

    private boolean atPlatform(ServerLevel level, Mob body) {
        return Reach.stanceHolding(body.position(), platform(level)).isPresent();
    }

    private void walkToPlatform(ServerLevel level, Mob body) {
        if (!body.getNavigation().isDone()) { return; }
        Set<BlockPos> goals = platform(level);
        var traffic = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, body.getBoundingBox().inflate(12));
        goals.removeIf(cell -> {
            double half = body.getBbWidth() / 2.0D;
            var space = new net.minecraft.world.phys.AABB(cell.getX() + 0.5D - half, cell.getY(), cell.getZ() + 0.5D - half,
                cell.getX() + 0.5D + half, cell.getY() + body.getBbHeight(), cell.getZ() + 0.5D + half);
            return traffic.stream().anyMatch(other -> other != body && other.isAlive() && other.getBoundingBox().intersects(space));
        });
        if (goals.isEmpty()) { return; }
        var path = body.getNavigation().createPath(goals, 0);
        if (path != null) { body.getNavigation().moveTo(path, 1.0D); }
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
