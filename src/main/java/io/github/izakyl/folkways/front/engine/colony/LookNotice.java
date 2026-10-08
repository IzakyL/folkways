package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.front.api.notice.NoticeKind;
import java.util.Locale;

public enum LookNotice implements NoticeKind {

    MEMBER,

    NON_MEMBER,

    LACKING,

    NO_ROOM;

    @Override
    public String translationKey() {
        return "folkways.look." + name().toLowerCase(Locale.ROOT);
    }
}
