package io.github.izakyl.folkways.plugins.rail.domain;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

public enum RailRefusal implements RefusalKind.Named {

    SEAT_TAKEN,

    SEAT_GONE,

    ANOTHER_COLONY,

    // The last run the rider could take has left the platform without them.
    RUN_MISSED,

    // The train no longer runs between the rider's stops by a timetable: its schedule changed, broke the rules for
    // one, or it lost its driver.
    LINE_WITHDRAWN;
}
