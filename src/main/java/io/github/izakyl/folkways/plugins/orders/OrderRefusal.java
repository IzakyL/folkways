package io.github.izakyl.folkways.plugins.orders;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

public enum OrderRefusal implements RefusalKind.Named {

    TOO_MANY,

    UNKNOWN_ITEM,

    NOT_A_CONTAINER,

    NOT_ADMITTED,

    ORDERED_HERE;
}
