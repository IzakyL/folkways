package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import java.util.Locale;

public enum LaborRefusal implements RefusalKind {

    NO_WAY_THERE,
    WORK_BROKE,
    PROTECTED,
    NOWHERE_TO_PUT_IT,
    GOODS_GONE,
    WAITS_ON_ITSELF,
    NO_ONE_FIT,
    BEFORE_NOT_DONE,
    // Why the plan could not grow asked work, told of the goods it went short of.
    NO_SOURCE,
    NO_ROOM_FOR,
    NO_WAY_FOR,
    NO_ONE_MAKES;

    @Override
    public String translationKey() {
        return "folkways.refusal.labor." + name().toLowerCase(Locale.ROOT);
    }

    @Override
    public Shortfall shortfall() {
        return switch (this) {
            case GOODS_GONE, NO_SOURCE -> Shortfall.LACKING;
            case NOWHERE_TO_PUT_IT, NO_ROOM_FOR -> Shortfall.NO_ROOM;
            default -> Shortfall.NONE;
        };
    }

    @Override
    public boolean lasting() {
        return switch (this) {
            case NO_WAY_THERE, NOWHERE_TO_PUT_IT, NO_ONE_FIT, NO_SOURCE, NO_ROOM_FOR, NO_WAY_FOR, NO_ONE_MAKES -> true;
            default -> false;
        };
    }
}
