package io.github.izakyl.folkways.core.api.terms;

import java.util.Locale;

public interface RefusalKind {

    String translationKey();

    /** What the refusal says of the goods its work names: that there are none to hand, or nowhere for them to go. */
    enum Shortfall {
        NONE,
        LACKING,
        NO_ROOM
    }

    default Shortfall shortfall() {
        return Shortfall.NONE;
    }

    /**
     * Whether the refusal names a state of the world that stays until someone changes it: told where the work is, not
     * only on the resident who met it. A refusal the next plan answers by itself, such as goods gone or the work
     * already done, is not.
     */
    default boolean lasting() {
        return false;
    }

    interface Named extends RefusalKind {

        @Override
        default String translationKey() {
            Enum<?> kind = (Enum<?>) this;
            String area = kind.getDeclaringClass().getSimpleName().replaceFirst("Refusal$", "");
            return ("folkways.refusal." + area + "." + kind.name()).toLowerCase(Locale.ROOT);
        }
    }
}
