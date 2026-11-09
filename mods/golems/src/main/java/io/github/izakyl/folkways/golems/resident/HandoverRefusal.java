package io.github.izakyl.folkways.golems.resident;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import java.util.Locale;

public enum HandoverRefusal implements RefusalKind {

    NOT_YOURS_TO_HAND_OVER,

    ALREADY_SETTLED_ELSEWHERE;

    @Override
    public String translationKey() {
        return "folkways.refusal.golem." + name().toLowerCase(Locale.ROOT);
    }
}
