package io.github.izakyl.folkways.core.api.terms;

public final class Gait {

    public static final double BLOCKS_PER_TICK = 0.165D;

    private Gait() {
    }

    public static int ticksToWalk(double blocks) {
        return (int) Math.ceil(blocks / BLOCKS_PER_TICK);
    }
}
