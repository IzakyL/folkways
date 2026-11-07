package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.front.api.notice.Attempt;
import io.github.izakyl.folkways.front.engine.Enrollments;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

public final class ZoneActions {

    private ZoneActions() {
    }

    public static Optional<Attempt> act(ServerLevel level, Colony colony, UUID zone, String key) {
        return ColonyFront.of(colony).zone(zone)
            .filter(area -> area.dimension().equals(level.dimension()))
            .flatMap(area -> Enrollments.delegation(area.delegation()))
            .flatMap(delegation -> delegation.actions().stream().filter(action -> action.key().equals(key)).findFirst())
            .map(action -> action.handler().run(level, colony, zone));
    }
}
