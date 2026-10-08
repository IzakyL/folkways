package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

enum PastureRefusal implements RefusalKind.Named {

    BEAST_GONE,

    BEAST_TAKEN,

    NOTHING_TO_TAKE,

    NOTHING_TO_WORK_WITH,

    NOT_FEEDABLE,

    STILL_STANDING;

    @Override
    public Shortfall shortfall() {
        return this == NOTHING_TO_WORK_WITH ? Shortfall.LACKING : Shortfall.NONE;
    }
}
