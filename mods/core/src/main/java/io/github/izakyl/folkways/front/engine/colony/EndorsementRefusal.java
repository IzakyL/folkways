package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import java.util.Locale;

public enum EndorsementRefusal implements RefusalKind {

    NO_ROOM_IN_THE_WORLD,

    NOBODY_TAKES_ANYONE_IN,

    HELD_ELSEWHERE;

    @Override
    public String translationKey() {
        return "folkways.refusal.endorsement." + name().toLowerCase(Locale.ROOT);
    }
}
