package io.github.izakyl.folkways.front.engine.charter;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

// Why a change to who keeps a colony was turned down.
public enum CharterRefusal implements RefusalKind.Named {

    FOUNDER_ONLY,

    NO_SUCH_PLAYER,

    ALREADY_MEMBER,

    NOT_MEMBER,

    ALREADY_INVITED,

    NOT_INVITED,

    LAST_MEMBER,

    KEPT_BY_TEAM,

    TEAM_OWNER_ONLY,

    NO_PARTY,

    BAD_NAME
}
