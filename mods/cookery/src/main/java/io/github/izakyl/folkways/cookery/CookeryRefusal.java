package io.github.izakyl.folkways.cookery;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

public enum CookeryRefusal implements RefusalKind.Named {
    NOT_A_STATION,
    STATION_BUSY,
    NOTHING_TO_WORK_WITH,
    STATION_CHANGED,
    RECIPE_CHANGED,
    NO_HEAT_SOURCE;

    @Override
    public boolean lasting() {
        return this == NOT_A_STATION || this == NO_HEAT_SOURCE;
    }

    @Override
    public Shortfall shortfall() {
        return this == NOTHING_TO_WORK_WITH ? Shortfall.LACKING : Shortfall.NONE;
    }
}