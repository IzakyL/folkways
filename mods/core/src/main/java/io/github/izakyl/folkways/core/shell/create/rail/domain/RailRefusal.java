package io.github.izakyl.folkways.core.shell.create.rail.domain;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;

public enum RailRefusal implements RefusalKind.Named {

    SEAT_GONE,

    // The last run the rider could take has left the platform without them.
    RUN_MISSED,

    // The train no longer runs between the rider's stops by a timetable: its schedule changed, broke the rules for
    // one, or it lost its driver.
    LINE_WITHDRAWN,

    STRANDED;
}
