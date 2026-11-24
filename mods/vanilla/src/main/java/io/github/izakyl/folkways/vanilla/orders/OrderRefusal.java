package io.github.izakyl.folkways.vanilla.orders;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;

public enum OrderRefusal implements RefusalKind.Named {

    TOO_MANY,

    NO_ITEM,

    UNKNOWN_ITEM,

    NOT_A_CONTAINER,

    NO_COUNT,

    TOO_LARGE,

    STANDING,

    COUNTING;
}
