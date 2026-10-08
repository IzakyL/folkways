package io.github.izakyl.folkways.plugins.build.draft;

public enum Fit {
    SOLID,

    STEPPED,

    LAYERED,

    CONNECTED;

    boolean partial() {
        return this == STEPPED || this == LAYERED;
    }
}
