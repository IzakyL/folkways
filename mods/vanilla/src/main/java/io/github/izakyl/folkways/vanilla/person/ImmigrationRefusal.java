package io.github.izakyl.folkways.vanilla.person;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;

public enum ImmigrationRefusal implements RefusalKind.Named {

    NOBODY_WAITING,

    NO_ROOM_IN_THE_WORLD,

    SWITCHED_OFF,

    NOT_ON_THE_ROSTER,

    SETTLED_FOR_GOOD;
}
