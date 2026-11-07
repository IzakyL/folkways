package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import io.github.izakyl.folkways.core.travel.internal.Trip;
import io.github.izakyl.folkways.core.travel.internal.Urgency;
import io.github.izakyl.folkways.core.travel.internal.Ways;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.ActionServices;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// The engine's navigation and diagnostics, without any node or phase registration.
public final class HoldingActions implements ActionServices {
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    private final Function<ServerLevel, Optional<Ways>> ways;
    private final BiConsumer<UUID, String> broke;

    public HoldingActions(Function<ServerLevel, Optional<Ways>> ways, BiConsumer<UUID, String> broke) {
        this.ways = ways;
        this.broke = broke;
    }

    @Override
    public Optional<Conveyance> approach(HoldingContext context, WorkAction action, Set<WorldPos> footings) {
        Body body = context.body();
        double speed = action.spec().pace();
        if (action.spec().vocation().filter(trade -> trade.id().equals(Vocations.HAULING)).isPresent()) {
            speed *= body.effects().value(HaulWork.TRAVEL_SPEED);
        }
        double travelSpeed = speed;
        if (action.directTravel()) {
            return Trip.afoot(body.locomotion(), footings, travelSpeed, noWay()).map(trip -> trip);
        }
        return ways.apply(context.level())
            .map(ways -> Trip.to(() -> this.ways.apply(context.level()).orElse(ways), body.resident(),
                body.locomotion(), body.mob(), footings, travelSpeed, noWay(), Urgency.WALKING, ColonyWays.FEEDBACK))
            .orElseGet(() -> Trip.afoot(body.locomotion(), footings, travelSpeed, noWay()))
            .map(trip -> trip);
    }

    @Override
    public void toolBroke(HoldingContext context, String item) {
        broke.accept(context.body().resident().id(), item);
    }

    @Override
    public RefusalKind noWay() {
        return LaborRefusal.NO_WAY_THERE;
    }

    @Override
    public RefusalKind travelFailure(RefusalKind why) {
        return why instanceof LaborRefusal named ? named : LaborRefusal.NO_WAY_THERE;
    }

    @Override
    public RefusalKind broken() {
        return LaborRefusal.WORK_BROKE;
    }

    @Override
    public void failed(WorkAction action, Throwable failure) {
        LOGGER.error("action {} threw on commit", action, failure);
    }
}
