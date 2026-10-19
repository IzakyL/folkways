package io.github.izakyl.folkways.create.dispatch;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

public enum DispatchRefusal implements RefusalKind.Named {

    NO_PORT,

    NOTHING_ARRIVED,

    NO_SUPPLY,

    REQUEST_REJECTED,

    SEAT_TAKEN,

    ANOTHER_COLONY,

    NOT_YOURS,

    NO_NETWORK,

    NOT_PACKED,

    PORT_FULL,

    OFF_CHAIN,

    CHAIN_ELSEWHERE;

    @Override
    public boolean lasting() {
        return switch (this) {
            case NO_PORT, NOT_PACKED, PORT_FULL -> true;
            default -> false;
        };
    }
}
