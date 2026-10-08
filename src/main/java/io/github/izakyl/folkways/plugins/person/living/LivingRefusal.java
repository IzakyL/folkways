package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

public enum LivingRefusal implements RefusalKind.Named {

    NOTHING_TO_EAT,
    NOT_ON_THE_BOOKS;

    @Override
    public Shortfall shortfall() {
        return this == NOTHING_TO_EAT ? Shortfall.LACKING : Shortfall.NONE;
    }
}
