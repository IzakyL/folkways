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

    interface Named extends RefusalKind {

        @Override
        default String translationKey() {
            Enum<?> kind = (Enum<?>) this;
            String area = kind.getDeclaringClass().getSimpleName().replaceFirst("Refusal$", "");
            return ("folkways.refusal." + area + "." + kind.name()).toLowerCase(Locale.ROOT);
        }
    }
}
