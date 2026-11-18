package io.github.izakyl.folkways.create.transport;

import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.resident.api.Headway;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

// The passenger's part of a seated ride. Services supply stop and availability checks; this class never
// chooses where the vehicle goes or assumes that it follows a timetable.
public abstract class SeatedConveyance<S> implements Conveyance {

    protected static final int STALLED_TICKS = 30 * 20;

    private final Stances.Cells boarding;
    private final Stances.Cells landing;
    private final IntSupplier seatTicks;
    private final ResourceLocation boardingActivity;
    private final ResourceLocation ridingActivity;
    private final RefusalKind seatGone;
    private final RefusalKind stranded;

    private Set<WorldPos> onward = Set.of();

    private final Headway ridden = new Headway();

    private Doing doing = Doing.open(Doings.WAITING);

    private int seating;
    private boolean boarded;
    private boolean alighted;
    private S taking;
    private Vec3 lastProgress;
    private long progressAt;
    private Long strandedAt;

    protected SeatedConveyance(Stances.Cells boarding, Stances.Cells landing, IntSupplier seatTicks,
            ResourceLocation boardingActivity, ResourceLocation ridingActivity, RefusalKind seatGone,
            RefusalKind stranded) {
        this.boarding = boarding;
        this.landing = landing;
        this.seatTicks = seatTicks;
        this.boardingActivity = boardingActivity;
        this.ridingActivity = ridingActivity;
        this.seatGone = seatGone;
        this.stranded = stranded;
    }

    protected abstract boolean available(ServerLevel level);

    protected abstract Optional<RefusalKind> boardingFailure(ServerLevel level);

    // ARRIVED means that the vehicle is at the boarding stop; otherwise wait or report the service's refusal.
    protected abstract Going awaitVehicle(ServerLevel level);

    protected abstract Optional<S> freeSeat(ServerLevel level);

    protected abstract boolean occupied(ServerLevel level, S seat);

    protected abstract boolean board(ServerLevel level, S seat, Mob body);

    protected abstract boolean atDestination(ServerLevel level);

    protected abstract Optional<S> seatOf(ServerLevel level, Mob body);

    protected abstract void disembark(ServerLevel level, S seat, Mob body);

    // A service may have stopped running while its seats are still accessible for cancellation.
    protected boolean canRelease(ServerLevel level) {
        return available(level);
    }

    protected void driving(ServerLevel level, Mob body) {
    }

    protected boolean rideWithdrawn(ServerLevel level) {
        return !available(level);
    }

    protected boolean betweenStops(ServerLevel level) {
        return false;
    }

    protected void abandonSeat(ServerLevel level, Mob body) {
        seatOf(level, body).ifPresent(seat -> disembark(level, seat, body));
    }

    @Override
    public Going step(ServerLevel level, Mob body, Set<WorldPos> to) {
        if (alighted) {
            return Going.ARRIVED;
        }
        onward = to;
        if (strandedAt != null && body.getVehicle() == null) {
            return new Going.Failed(stranded);
        }
        if (boarded && body.getVehicle() != null) {
            return riding(level, body);
        }
        if (boarded || body.getVehicle() != null || !available(level)) {
            return new Going.Failed(seatGone);
        }
        return boarding(level, body);
    }

    private Going boarding(ServerLevel level, Mob body) {
        doing = Doing.open(Doings.WAITING);
        Optional<RefusalKind> failure = boardingFailure(level);
        if (failure.isPresent()) {
            return new Going.Failed(failure.get());
        }
        Set<BlockPos> boarding = cellsIn(level, this.boarding.cells());
        boolean standing = Reach.stanceHolding(body.position(), boarding).isPresent();
        if (!standing) {
            stepBackOn(body, boarding);
        }
        Going waiting = awaitVehicle(level);
        if (!(waiting instanceof Going.Arrived)) {
            taking = null;
            seating = 0;
            return waiting;
        }
        if (!standing) {
            return Going.UNDERWAY;
        }
        if (taking == null || occupied(level, taking)) {
            taking = freeSeat(level).orElse(null);
            seating = 0;
        }
        if (taking == null) {
            return Going.UNDERWAY;
        }

        int duration = seatTicks.getAsInt();
        doing = Doing.along(boardingActivity, seating / (float) Math.max(1, duration));
        if (++seating < duration) {
            return Going.UNDERWAY;
        }
        if (board(level, taking, body)) {
            boarded = true;
            driving(level, body);
        } else {
            taking = null;
            seating = 0;
        }
        return Going.UNDERWAY;
    }

    // The walk here ended standing on the boarding cells, but a crowded platform jostles a rider off them while it
    // waits, and nothing else brings it back: it steps back on to the nearest itself, or it would wait for good.
    private static void stepBackOn(Mob body, Set<BlockPos> boarding) {
        if (boarding.isEmpty() || !body.getNavigation().isDone()) {
            return;
        }
        Path path = body.getNavigation().createPath(boarding, 0);
        if (path != null) {
            body.getNavigation().moveTo(path, 1.0D);
        }
    }

    private Going riding(ServerLevel level, Mob body) {
        driving(level, body);
        carried(level, body);
        if (strandedAt == null) {
            if (atDestination(level)) {
                alight(level, body);
                if (alighted) {
                    return Going.UNDERWAY;
                }
            }
            if (!rideWithdrawn(level) && !stalled(level, body)) {
                return Going.UNDERWAY;
            }
            strandedAt = level.getGameTime();
        }
        Optional<BlockPos> safe = PassengerLanding.nearby(level, body);
        if (safe.isEmpty() && level.getGameTime() - strandedAt >= PassengerLanding.PLATFORM_FALLBACK_TICKS) {
            safe = fallbackLanding(level, body).or(() -> PassengerLanding.widerSearch(level, body));
        }
        if (safe.isPresent()) {
            abandonSeat(level, body);
            if (body.getVehicle() == null) {
                PassengerLanding.place(body, safe.get());
                return new Going.Failed(stranded);
            }
        }
        return Going.UNDERWAY;
    }

    private boolean stalled(ServerLevel level, Mob body) {
        Vec3 position = body.getVehicle().position();
        long now = level.getGameTime();
        if (lastProgress == null || !betweenStops(level) || lastProgress.distanceToSqr(position) >= 0.01D) {
            lastProgress = position;
            progressAt = now;
        }
        return now - progressAt >= STALLED_TICKS;
    }

    protected Optional<BlockPos> fallbackLanding(ServerLevel level, Mob body) {
        return landingPlatform(level).flatMap(platform -> PassengerLanding.localPlatform(level, body, platform))
            .or(() -> PassengerLanding.localPlatform(level, body, boarding));
    }

    private void carried(ServerLevel level, Mob body) {
        Set<BlockPos> platform = cellsIn(level, landing.cells());
        if (platform.isEmpty()) {
            doing = Doing.open(ridingActivity);
            return;
        }
        double apart = Math.sqrt(towards(platform, body.blockPosition()));
        if (ridden.fraction().isEmpty()) {
            ridden.setOut(apart);
        } else {
            ridden.closer(apart);
        }
        doing = ridden.fraction()
            .map(done -> Doing.along(ridingActivity, done))
            .orElseGet(() -> Doing.open(ridingActivity));
    }

    @Override
    public Optional<Doing> doing() {
        return alighted ? Optional.empty() : Optional.of(doing);
    }

    private void alight(ServerLevel level, Mob body) {
        BlockPos landing = platformLanding(level);
        if (landing == null) {
            return;
        }
        seatOf(level, body).ifPresent(seat -> {
            disembark(level, seat, body);
            if (body.getVehicle() != null) {
                return;
            }
            PassengerLanding.place(body, landing);
            alighted = true;
        });
    }

    private BlockPos platformLanding(ServerLevel level) {
        return landingPlatform(level)
            .flatMap(platform -> PassengerLanding.at(level, platform, onward)).orElse(null);
    }

    protected Optional<Stances.Cells> landingPlatform(ServerLevel level) {
        return Optional.of(landing);
    }

    private static double towards(Set<BlockPos> goals, BlockPos cell) {
        double nearest = Double.MAX_VALUE;
        for (BlockPos goal : goals) {
            nearest = Math.min(nearest, cell.distSqr(goal));
        }
        return nearest;
    }

    private static Set<BlockPos> cellsIn(ServerLevel level, Set<WorldPos> cells) {
        Set<BlockPos> here = new LinkedHashSet<>(cells.size());
        for (WorldPos cell : cells) {
            if (cell.in(level)) {
                here.add(cell.block(level));
            }
        }
        return here;
    }

    @Override
    public void release(ServerLevel level, Mob body) {
        if (body.getVehicle() == null) {
            return;
        }
        if (boarded && strandedAt == null && canRelease(level) && atDestination(level)) {
            alight(level, body);
        }
    }
}
