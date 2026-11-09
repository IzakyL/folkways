package io.github.izakyl.folkways.create.dispatch;

import io.github.izakyl.folkways.core.front.api.notice.NoticeKind;
import java.util.Locale;

enum DispatchNotice implements NoticeKind {

    SENDING;

    @Override
    public String translationKey() {
        return "folkways.look." + name().toLowerCase(Locale.ROOT);
    }
}
