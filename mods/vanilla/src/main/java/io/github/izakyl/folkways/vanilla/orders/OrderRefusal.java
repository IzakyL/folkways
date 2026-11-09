package io.github.izakyl.folkways.vanilla.orders;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;

public enum OrderRefusal implements RefusalKind.Named {

    TOO_MANY,

    UNKNOWN_ITEM,

    NOT_A_CONTAINER;
}
