package io.github.izakyl.folkways.carts;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

public enum CartRefusal implements RefusalKind.Named {

    NO_CART,

    NO_HORSE,

    NOT_HITCHED,

    NOT_AT_STATION,

    NO_ROOM,

    NOT_ABOARD,

    NOWHERE_TO_PLACE
}
