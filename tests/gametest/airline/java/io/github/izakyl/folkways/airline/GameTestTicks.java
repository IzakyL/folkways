package io.github.izakyl.folkways.airline;

import net.minecraft.gametest.framework.GameTestHelper;

/** Repeating assertions without preallocating and scanning one scheduled task per remaining timeout tick. */
public final class GameTestTicks {
    private GameTestTicks() {
    }

    public static void onEachTick(GameTestHelper helper, Runnable action) {
        ((Repeating) helper.testInfo).airline$onEachTick(action);
    }

    public interface Repeating {
        void airline$onEachTick(Runnable action);

        long airline$testTick();
    }
}
