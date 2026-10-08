package io.github.izakyl.folkways.core.engine.plan.haul;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import java.util.Locale;

public enum HaulRefusal implements RefusalKind {

    NOT_A_CONTAINER,
    NOTHING_TO_TAKE,
    NOTHING_CARRIED,
    NO_ROOM,
    PACK_FULL,
    NOTHING_FREE;

    @Override
    public String translationKey() {
        return "folkways.refusal.haul." + name().toLowerCase(Locale.ROOT);
    }

    @Override
    public Shortfall shortfall() {
        return switch (this) {
            case NOTHING_TO_TAKE, NOTHING_CARRIED -> Shortfall.LACKING;
            case NO_ROOM, PACK_FULL -> Shortfall.NO_ROOM;
            default -> Shortfall.NONE;
        };
    }
}
