package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.front.api.notice.NoticeKind;
import java.util.Locale;

enum PastureNotice implements NoticeKind {

    UNFENCED,

    SPLIT;

    @Override
    public String translationKey() {
        return "folkways.look.pasture." + name().toLowerCase(Locale.ROOT);
    }
}
