package io.github.izakyl.folkways.vanilla.create.rail.domain;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import java.util.Locale;

enum ConductorRefusal implements RefusalKind {

    SEAT_TAKEN,

    ANOTHER_COLONY;

    @Override
    public String translationKey() {
        return "folkways.refusal.rail." + name().toLowerCase(Locale.ROOT);
    }
}
