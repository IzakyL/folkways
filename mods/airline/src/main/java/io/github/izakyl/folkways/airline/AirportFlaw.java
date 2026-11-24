package io.github.izakyl.folkways.airline;

import io.github.izakyl.folkways.core.card.api.notice.NoticeKind;
import java.util.Locale;

public enum AirportFlaw implements NoticeKind {
    UNNAMED,
    SHARED_NAME,
    NAME_MISMATCH,
    NO_LANDING_AREA,
    STAND_UNREACHABLE,
    APPROACH_OBSTRUCTED,
    UNLOADED,
    OVERLAP,
    SURVEY_TOO_LARGE,
    RUNWAY_UNREACHABLE;

    @Override
    public String translationKey() {
        return "folkways.airline.airport.flaw." + name().toLowerCase(Locale.ROOT);
    }
}
