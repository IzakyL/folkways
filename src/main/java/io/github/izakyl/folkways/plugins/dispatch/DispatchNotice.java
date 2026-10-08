package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.front.api.notice.NoticeKind;
import java.util.Locale;

enum DispatchNotice implements NoticeKind {

    SENDING;

    @Override
    public String translationKey() {
        return "folkways.look." + name().toLowerCase(Locale.ROOT);
    }
}
