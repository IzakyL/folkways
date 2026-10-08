package io.github.izakyl.folkways.plugins.golem.domain;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

public enum GolemRefusal implements RefusalKind.Named {

    NOTHING_TO_MEND_WITH;

    @Override
    public Shortfall shortfall() {
        return Shortfall.LACKING;
    }
}
