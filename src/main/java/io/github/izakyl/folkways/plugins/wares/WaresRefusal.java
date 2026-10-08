package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

public enum WaresRefusal implements RefusalKind.Named {

    NOT_A_STATION,
    NOTHING_TO_WORK_WITH,
    STATION_FULL,
    STATION_BUSY;

    @Override
    public Shortfall shortfall() {
        return switch (this) {
            case NOTHING_TO_WORK_WITH -> Shortfall.LACKING;
            case STATION_FULL -> Shortfall.NO_ROOM;
            default -> Shortfall.NONE;
        };
    }
}
