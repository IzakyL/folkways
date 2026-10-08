package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

public enum DispatchRefusal implements RefusalKind.Named {

    NO_PORT,

    NOTHING_ARRIVED,

    NO_SUPPLY,

    REQUEST_REJECTED,

    SEAT_TAKEN,

    ANOTHER_COLONY,

    NOT_YOURS,

    NO_NETWORK;
}
