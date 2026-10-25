package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

public enum GroundRefusal implements RefusalKind.Named {

    NOTHING_TO_WORK,
    INVALID_FILL,
    ORDER_TOO_LARGE,
    ORDER_UNAVAILABLE;
}
