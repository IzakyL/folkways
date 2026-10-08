package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import java.util.Locale;

public enum DraftRefusal implements RefusalKind {
    SITE_TOO_LARGE,
    PATTERN_FAILED,
    NOTHING_DRAWN,
    NOTHING_TO_BUILD_WITH,
    NO_GROUND,
    UNLOADED,
    READ_TOO_FAR,
    READ_BUDGET,
    WRONG_MARK,
    UNKNOWN_BLOCK;

    @Override
    public String translationKey() {
        return "folkways.refusal.draft." + name().toLowerCase(Locale.ROOT);
    }
}
