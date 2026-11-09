package io.github.izakyl.folkways.vanilla.pasture;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;

enum PastureRefusal implements RefusalKind.Named {

    HIVE_GONE,

    HIVE_TAKEN,

    HIVE_NOT_READY,

    HIVE_NOT_SMOKED,

    BEAST_GONE,

    BEAST_TAKEN,

    NOTHING_TO_TAKE,

    NOTHING_TO_WORK_WITH,

    NOT_FEEDABLE,

    HERD_CHANGED,

    STILL_STANDING;

    @Override
    public Shortfall shortfall() {
        return this == NOTHING_TO_WORK_WITH ? Shortfall.LACKING : Shortfall.NONE;
    }

    @Override
    public boolean lasting() {
        return this == NOT_FEEDABLE;
    }
}
