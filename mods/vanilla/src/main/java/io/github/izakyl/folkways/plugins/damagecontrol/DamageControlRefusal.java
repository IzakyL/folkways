package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import java.util.Locale;

public enum DamageControlRefusal implements RefusalKind {
    NO_REACH,
    CONFLICT,
    NOT_LOADED,
    UNSUPPORTED,
    NO_WATER,
    BUSY;

    @Override
    public boolean lasting() {
        return this != BUSY;
    }

    @Override
    public String translationKey() {
        return "folkways.refusal.damage_control." + name().toLowerCase(Locale.ROOT);
    }
}
