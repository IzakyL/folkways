package io.github.izakyl.folkways.core.work.support;

import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.ActionServices;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.Seating;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;

// Navigation mechanics shared by planned approaches and private actions; arrival belongs to the caller. A seat
// stance is arrived at by walking to where the seat is boarded from and sitting down, if the work admits the body.
public final class Walking {
    // A seat may refuse a body for a moment (a vehicle's dismount cooldown) before it is given up.
    private static final int SIT_PATIENCE = 100;

    private final WorkAction action;
    private final Stances footing;
    private Conveyance carrying;
    private Set<WorldPos> boarding = Set.of();
    private int sitting;
    private boolean awaitingSeat;

    public Walking(WorkAction action, Placement placement) {
        this.action = action;
        footing = placement.stands().isEmpty() || action.spec().stances() instanceof Stances.Seat
            ? action.spec().stances()
            : Stances.of(placement.stands().stream().map(Stand::cell).toList())
                .orElseGet(() -> action.spec().stances());
    }

    private enum Sitting {
        SEATED,
        AWAY,
        REFUSED,
        WAITING
    }

    // Sits the body down when it stands where the seat is boarded from.
    private Sitting sit(Body body, Stances.Seat seat, boolean there) {
        if (!(body.mob().level() instanceof ServerLevel level)) {
            return Sitting.AWAY;
        }
        if (Seating.in(body.mob(), seat)) {
            return Seating.sit(level, body.mob(), seat) ? Sitting.SEATED : Sitting.WAITING;
        }
        if (!there && (boarding.isEmpty() || !WorkStances.standingIn(body.mob(), new Stances.Cells(boarding)))) {
            return Sitting.AWAY;
        }
        if (!action.admits(level, body.mob())) {
            return Sitting.REFUSED;
        }
        return Seating.sit(level, body.mob(), seat) ? Sitting.SEATED : Sitting.WAITING;
    }

    /** Stands the body up from this walk's seat, if the engine sat it there. */
    public void rise(Body body) {
        if (footing instanceof Stances.Seat seat) {
            Seating.rise(body.mob(), seat);
        }
    }

    public boolean travelling() {
        return carrying != null || awaitingSeat;
    }

    public boolean standing(Body body) {
        boolean atStance = footing instanceof Stances.Seat seat ? Seating.in(body.mob(), seat)
            && sit(body, seat, true) == Sitting.SEATED
            : footing instanceof Stances.Wherever || WorkStances.standingIn(body.mob(), footing);
        return action instanceof Node node && body.mob().level() instanceof ServerLevel level
            ? node.arrived(level, body, footing, atStance) : atStance;
    }

    public HoldingStep start(HoldingContext context, ActionServices services) {
        Body body = context.body();
        carrying = null;
        sitting = 0;
        awaitingSeat = false;
        if (footing instanceof Stances.Seat seat) {
            if (!Seating.in(body.mob(), seat)) {
                // Work elsewhere first stands up from a seat other work sat the body in.
                Seating.rise(body.mob());
            }
            boarding = Seating.footings(context.level(), seat, body.mob());
            Optional<HoldingStep> seated = seated(body, seat, false, services);
            if (seated.isPresent()) {
                return seated.get();
            }
            if (boarding.isEmpty()) {
                return new HoldingStep.Failed(services.noWay());
            }
        } else if (standing(body)) {
            return new HoldingStep.Done();
        }
        Optional<Conveyance> trip = services.approach(context, action, goals());
        if (trip.isEmpty()) {
            return new HoldingStep.Failed(services.noWay());
        }
        carrying = trip.get();
        body.setDoing(heading());
        carrying.setOut(context.level(), body.mob(), goals());
        underway(body);
        return new HoldingStep.Running();
    }

    private Set<WorldPos> goals() {
        return footing instanceof Stances.Seat ? boarding : footing.cells();
    }

    // Arrival at a seat: done once seated, failed when the work turns the body away; empty while it is not there.
    private Optional<HoldingStep> seated(Body body, Stances.Seat seat, boolean there, ActionServices services) {
        Sitting now = sit(body, seat, there);
        awaitingSeat = now == Sitting.WAITING && sitting < SIT_PATIENCE;
        return switch (now) {
            case SEATED -> Optional.of(new HoldingStep.Done());
            case REFUSED -> Optional.of(new HoldingStep.Failed(LaborRefusal.NO_ONE_FIT));
            case WAITING -> Optional.of(sitting++ < SIT_PATIENCE ? new HoldingStep.Running()
                : new HoldingStep.Failed(services.noWay()));
            case AWAY -> Optional.empty();
        };
    }

    public HoldingStep tick(HoldingContext context, ActionServices services) {
        Body body = context.body();
        if (carrying == null || !carrying.awaitingArrival()) {
            if (footing instanceof Stances.Seat seat) {
                Optional<HoldingStep> seated = seated(body, seat, carrying == null, services);
                if (seated.isPresent()) {
                    release(body);
                    return seated.get();
                }
                if (carrying == null) {
                    return new HoldingStep.Failed(services.noWay());
                }
            } else if (standing(body)) {
                release(body);
                return new HoldingStep.Done();
            }
        }
        return switch (carrying.step(context.level(), body.mob(), goals())) {
            case Going.Arrived ignored -> {
                release(body);
                if (footing instanceof Stances.Seat seat) {
                    yield seated(body, seat, true, services)
                        .orElseGet(() -> new HoldingStep.Failed(services.noWay()));
                }
                yield new HoldingStep.Done();
            }
            case Going.Failed(RefusalKind why) -> {
                release(body);
                yield new HoldingStep.Failed(services.travelFailure(why));
            }
            case Going.Underway ignored -> {
                underway(body);
                yield new HoldingStep.Running();
            }
        };
    }

    public void release(Body body) {
        if (carrying != null && body.mob().level() instanceof ServerLevel level) {
            carrying.release(level, body.mob());
        }
        carrying = null;
    }

    private Doing heading() {
        return Doing.heading(action.spec().doing(), action.spec().about(), action.wares());
    }

    private void underway(Body body) {
        carrying.doing().map(doing -> doing.what().equals(Doings.WALKING) ? heading()
            : doing.headingFor(action.spec().doing(), action.spec().about(), action.wares())).ifPresent(body::setDoing);
    }
}
