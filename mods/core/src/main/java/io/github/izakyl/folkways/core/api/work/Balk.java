package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

/** Work a body gave up on: what it was doing, why it could not, and the game time it stopped. */
public record Balk(Doing doing, RefusalKind why, long at) {

    /** How long a balk stays news when nothing else has happened since. */
    public static final long FRESH_TICKS = 200L;

    public boolean fresh(long now) {
        return now - at < FRESH_TICKS;
    }
}
