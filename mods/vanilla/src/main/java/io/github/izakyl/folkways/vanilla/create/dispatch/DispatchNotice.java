package io.github.izakyl.folkways.vanilla.create.dispatch;

import io.github.izakyl.folkways.core.card.api.notice.NoticeKind;
import java.util.Locale;

enum DispatchNotice implements NoticeKind {

    SENDING;

    @Override
    public String translationKey() {
        return "folkways.look." + name().toLowerCase(Locale.ROOT);
    }
}
