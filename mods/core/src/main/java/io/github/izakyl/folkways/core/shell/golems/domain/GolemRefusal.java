package io.github.izakyl.folkways.core.shell.golems.domain;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;

public enum GolemRefusal implements RefusalKind.Named {

    NOTHING_TO_MEND_WITH;

    @Override
    public Shortfall shortfall() {
        return Shortfall.LACKING;
    }
}
