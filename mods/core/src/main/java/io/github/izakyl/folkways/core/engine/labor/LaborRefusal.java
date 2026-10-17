package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import java.util.Locale;

public enum LaborRefusal implements RefusalKind {

    NO_WAY_THERE,
    WORK_BROKE,
    NOWHERE_TO_PUT_IT,
    GOODS_GONE,
    WAITS_ON_ITSELF,
    NO_ONE_FIT,
    BEFORE_NOT_DONE;

    @Override
    public String translationKey() {
        return "folkways.refusal.labor." + name().toLowerCase(Locale.ROOT);
    }

    @Override
    public Shortfall shortfall() {
        return switch (this) {
            case GOODS_GONE -> Shortfall.LACKING;
            case NOWHERE_TO_PUT_IT -> Shortfall.NO_ROOM;
            default -> Shortfall.NONE;
        };
    }
}
