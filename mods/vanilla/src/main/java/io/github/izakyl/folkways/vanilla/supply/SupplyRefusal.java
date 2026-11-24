package io.github.izakyl.folkways.vanilla.supply;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;

public enum SupplyRefusal implements RefusalKind.Named {
    SOURCE_CHANGED,
    NO_ROOM;

    @Override
    public Shortfall shortfall() { return this == NO_ROOM ? Shortfall.NO_ROOM : Shortfall.NONE; }
}
