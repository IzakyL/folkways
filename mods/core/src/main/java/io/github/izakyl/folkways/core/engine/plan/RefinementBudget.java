package io.github.izakyl.folkways.core.engine.plan;

import java.util.function.LongSupplier;

// Optional improvement must finish before the next useful plan is held up by it. Count the cheap rejected
// candidates and pack scans too, with a clock as a second guard on an ordinary live server.
final class RefinementBudget {
    static final int WORK = 20_000;
    static final long NANOS = 100_000_000L;

    private final long limit;
    private final LongSupplier clock;
    private final long began;
    private final long nanos;
    private long spent;

    RefinementBudget(long limit, LongSupplier clock, long nanos) {
        this.limit = limit;
        this.clock = clock;
        this.began = clock.getAsLong();
        this.nanos = nanos;
    }

    void spend(long work) {
        if (Thread.currentThread().isInterrupted() || work > limit - spent
                || clock.getAsLong() - began >= nanos) {
            throw new Exhausted();
        }
        spent += work;
    }

    long spent() { return spent; }

    static final class Exhausted extends RuntimeException {
        Exhausted() { super(null, null, false, false); }
    }
}
