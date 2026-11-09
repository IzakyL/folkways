package io.github.izakyl.folkways.core.rts.combat.group;

import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.combat.api.GroupRoute;
import java.util.List;

/** RTS validation and command policy over the shared autonomous movement planner. */
final class PointRoute extends GroupRoute {

    PointRoute(UnitGroups owner, List<Body> bodies, List<WorldPos> points, int cycleStart) {
        this(owner, bodies, points, cycleStart, false);
    }

    PointRoute(UnitGroups owner, List<Body> bodies, List<WorldPos> points, int cycleStart, boolean appendable) {
        this(owner, bodies, points, cycleStart, appendable, false);
    }

    PointRoute(
        UnitGroups owner,
        List<Body> bodies,
        List<WorldPos> points,
        int cycleStart,
        boolean appendable,
        boolean attackMove) {
        super(owner.commands::validate, bodies, points, cycleStart, appendable, attackMove);
    }

    static PointRoute regroup(UnitGroups owner, List<Body> bodies) {
        WorldPos anchor = GroupRoute.regroup(owner.commands::validate, bodies).captain();
        PointRoute route = new PointRoute(owner, bodies, List.of(anchor), -1);
        route.advanced(anchor);
        return route;
    }
}
