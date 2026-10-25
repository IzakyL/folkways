package io.github.izakyl.folkways.plugins.person.living;

/** Transient observations only: loading, missed ticks and duplicate observers never invent awake time. */
final class AwakeTime {
    private long observed = Long.MIN_VALUE;

    int observe(long now, boolean awake) {
        boolean consecutive = observed != Long.MIN_VALUE && now - observed == 1;
        observed = now;
        return consecutive && awake ? 1 : 0;
    }
}
