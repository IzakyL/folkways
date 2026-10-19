package io.github.izakyl.folkways.plugins.fishing;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

public enum FishingRefusal implements RefusalKind.Named {

    NO_WATER,
    LINE_ALREADY_OUT;

    @Override
    public boolean lasting() {
        return this == NO_WATER;
    }
}
