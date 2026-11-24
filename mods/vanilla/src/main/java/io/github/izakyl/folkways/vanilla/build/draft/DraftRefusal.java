package io.github.izakyl.folkways.vanilla.build.draft;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import java.util.Locale;

public enum DraftRefusal implements RefusalKind {
    SITE_TOO_LARGE,
    PATTERN_FAILED,
    NOTHING_DRAWN,
    NOTHING_TO_BUILD_WITH,
    NO_GROUND,
    UNLOADED,
    READ_TOO_FAR,
    TIMED_OUT,
    MEMORY_LIMIT,
    WRONG_MARK,
    UNKNOWN_BLOCK,
    UNSUPPORTED_PAYLOAD,
    MISSING_VOXELIZER,
    JOINER_UNAVAILABLE;

    @Override
    public String translationKey() {
        return "folkways.refusal.draft." + name().toLowerCase(Locale.ROOT);
    }
}
