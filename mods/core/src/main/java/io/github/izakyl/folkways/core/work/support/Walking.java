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
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.world.api.Stand;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

// Navigation mechanics shared by planned approaches and private actions; arrival belongs to the caller.
public final class Walking {
    private final WorkAction action;
    private final Stances footing;
    private Conveyance carrying;

    public Walking(WorkAction action, Placement placement) {
        this.action = action;
        footing = placement.stands().isEmpty() ? action.spec().stances()
            : Stances.of(placement.stands().stream().map(Stand::cell).toList())
                .orElseGet(() -> action.spec().stances());
    }

    public boolean travelling() {
        return carrying != null;
    }

    public boolean standing(Body body) {
        boolean atStance = footing instanceof Stances.Wherever || WorkStances.standingIn(body.mob(), footing);
        return action instanceof Node node && body.mob().level() instanceof ServerLevel level
            ? node.arrived(level, body, footing, atStance) : atStance;
    }

    public HoldingStep start(HoldingContext context, ActionServices services) {
        Body body = context.body();
        carrying = null;
        if (standing(body)) {
            return new HoldingStep.Done();
        }
        Optional<Conveyance> trip = services.approach(context, action, footing.cells());
        if (trip.isEmpty()) {
            return new HoldingStep.Failed(services.noWay());
        }
        carrying = trip.get();
        body.setDoing(heading());
        carrying.setOut(context.level(), body.mob(), footing.cells());
        underway(body);
        return new HoldingStep.Running();
    }

    public HoldingStep tick(HoldingContext context, ActionServices services) {
        Body body = context.body();
        if (standing(body)) {
            release(body);
            return new HoldingStep.Done();
        }
        return switch (carrying.step(context.level(), body.mob(), footing.cells())) {
            case Going.Arrived ignored -> {
                release(body);
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
