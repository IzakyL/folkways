package io.github.izakyl.folkways.vanilla.farming;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;

public enum FarmRefusal implements RefusalKind.Named {

    NOTHING_TO_WORK,
    GROUND_TAKEN,
    NO_SICKLE,
    SICKLE_COOLDOWN;
}
