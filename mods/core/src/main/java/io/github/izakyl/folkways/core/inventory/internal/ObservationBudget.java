package io.github.izakyl.folkways.core.inventory.internal;

import io.github.izakyl.folkways.core.travel.internal.TickBudget;
import java.util.function.LongSupplier;
import net.minecraft.Util;

public final class ObservationBudget {

    private final LongSupplier clock;

    private long until;
    private long remaining;
    private int steps;
    private long began;

    public ObservationBudget(int steps) {
        this(steps, TickBudget.untimed() ? -1 : Long.MAX_VALUE, Util::getNanos);
    }

    ObservationBudget(int steps, long remaining, LongSupplier clock) {
        this.steps = steps;
        this.remaining = remaining;
        this.clock = clock;
        resume();
    }

    public void resume() {
        began = clock.getAsLong();
        until = remaining < 0 ? -1 : began + Math.min(remaining, TickBudget.remaining());
    }

    public void pause() {
        if (until >= 0) {
            long elapsed = clock.getAsLong() - began;
            remaining = Math.max(0, remaining - elapsed);
            TickBudget.spent(elapsed);
        }
    }

    public boolean spent() {
        return until >= 0 && clock.getAsLong() >= until || steps-- <= 0;
    }
}
