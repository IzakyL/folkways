package io.github.izakyl.folkways.core.front.engine.colony;

import io.github.izakyl.folkways.core.front.api.notice.NoticeKind;
import java.util.Locale;

public enum LookNotice implements NoticeKind {

    LACKING,

    NO_ROOM;

    @Override
    public String translationKey() {
        return "folkways.look." + name().toLowerCase(Locale.ROOT);
    }
}
