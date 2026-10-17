package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

public enum BuildRefusal implements RefusalKind.Named {
    UNKNOWN_BLOCK,
    NO_WAY_TO_BUILD,
    NO_STANCE,
    ACCESS_DEPENDENCY,
    CONTESTED,
    OCCUPIED,
    CELL_NOT_LOADED,
    NO_SUCH_ORDER;
}
